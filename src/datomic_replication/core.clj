(ns datomic-replication.core
  "Datomic Replication"
  (:require [clojure.core.async :as async :refer [go go-loop <! >!]]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [enos.core :refer [dochan!]])
  (:import [java.util Date]))


;;; Helpers

(defn- attr-def [ident type & [opts]]
  (let [type (keyword "db.type" (name type))]
    (merge {:db/id                 (d/tempid :db.part/db)
            :db/ident              ident
            :db/valueType          type
            :db/cardinality        :db.cardinality/one
            :db.install/_attribute :db.part/db}
           opts)))

(defn- transact-at [conn tx-instant datoms]
  (d/transact conn
              (conj datoms
                    {:db/id        (d/tempid :db.part/tx)
                     :db/txInstant tx-instant})))

(defn- partition-ident [db eid]
  (:db/ident (d/entity db (d/part eid))))


;;; 

(defn- init-dest-database
  "This is the default implementation of the `:init` function that you
  can pass to `replicator`.

  It does the following:

  - Creates the attribute :datomic-replication/source-eid in the
  destination database, which connects each entity to its corresponding
  entity in the source database.

  - Creates an entity to store replication metadata. This entity has
  the ident :datomic-replication/metadata-store, is in a partition
  called :datomic-replication, and has attributes:
    - :datomic-replication/source-t, long, the t-value in the source
      database of the last-replicated transaction

  This function receives the txInstant of the first transaction that
  will be replicated from the source database as a parameter, so that
  it can use it as the instant of this transaction."
  [conn tx-instant]
  ;; Note: this is idempotent (I think)
  @(transact-at conn
                tx-instant
                [(attr-def :datomic-replication/source-eid
                           :long
                           {:db/unique :db.unique/identity})
                 (attr-def :datomic-replication/source-t
                           :long)
                 {:db/id                 (d/tempid :db.part/db)
                  :db/ident              :datomic-replication
                  :db.install/_partition :db.part/db}])
  @(transact-at conn
                tx-instant
                [{:db/id    (d/tempid :datomic-replication)
                  :db/ident :datomic-replication/metadata}]))

(def ^:dynamic e->lookup-ref-default
  "Function that returns the :ident of an attribute to use as the
  database-independent identifier for the given entity."
  (fn [db eid]
    (let [ent (d/entity db eid)]
      (if-let [ident (:db/ident ent)]
        [:db/ident ident]
        [:datomic-replication/source-eid (:db/id ent)]))))


(defn transactions
  "Returns an async channel of transactions.
   Options include:
    - start-t - the `t` to start at
    - poll-interval - how long to pause when there are no new transactions
  "
  ([conn]
     (transactions conn {:start-t       nil
                         :poll-interval 100}))
  ([conn opts]
     (let [{:keys [start-t poll-interval]} opts
           ch        (async/chan)
           continue? (atom true)
           stopper   #(reset! continue? false)]
       (go-loop [start-t start-t]
         (when @continue?
           (let [txs (d/tx-range (d/log conn) start-t nil)]
             (if (seq txs)
               (do
                 (doseq [tx txs]
                   (when @continue?
                     (>! ch tx)))
                 (recur (inc (:t (last txs)))))
               (do
                 (<! (async/timeout poll-interval))
                 (recur start-t))))))
       [ch stopper])))


(defn replicate-tx
  "Sends the transaction to the connection."
  [{:keys [t data] :as tx} source-conn dest-conn e->lookup-ref]
  (log/info "Got tx: " {:t (:t tx) :count (count (:data tx))})
  (let [source-db    (d/as-of (d/db source-conn) t)
        dest-db      (d/db dest-conn)
        eids         (distinct (for [[e] data] e))

        ;; Mapping from each distinct eid to a database-independent
        ;; identifier for the entity - a lookup-ref in the form:
        ;; [attr-ident attr-value]. This will be one of:
        ;;  - [:db/ident <val>], for entities that have an ident
        ;;  - [:datomic-replication/source-eid <eid>] (default)
        ;;  - a domain-specific unique attribute+value pair
        ->lookup-ref (memoize
                      (fn [eid]
                        (e->lookup-ref source-db eid)))

        ;; Function to translate an eid from the source database into
        ;; one that is valid in the destination database.  This will
        ;; be either an actual eid, if the entity exists already, or a
        ;; tempid if the entity is new.
        ->dest-eid   (memoize
                      (fn [eid]
                        (let [lookup-ref (->lookup-ref eid)
                              dest-ent   (d/entity dest-db lookup-ref)]
                          (if dest-ent
                            (:db/id dest-ent)
                            (let [part (partition-ident source-db eid)]
                              (d/tempid part))))))

        datoms       (for [[e a v t added?] data
                           :let [[id-attr id-val] (->lookup-ref e)
                                 attr             (d/entity source-db a)
                                 attr-ident       (:db/ident attr)
                                 is-ref?          (= :db.type/ref (:db/valueType attr))
                                 v                (if is-ref?
                                                    (->dest-eid v)
                                                    v)]]
                       (if added?
                         (hash-map
                          :db/id     (->dest-eid e)
                          id-attr    id-val
                          attr-ident v)
                         [:db/retract
                          [id-attr id-val]
                          attr-ident
                          v]))

        metadata     [:db/add
                      :datomic-replication/metadata
                      :datomic-replication/source-t
                      t]]
    ;;(log/info "transacting:" datoms)
    (try
      @(d/transact dest-conn (conj datoms metadata))
      (catch Exception e
        (log/error e "Exception thrown by replicate-tx")
        (throw e)))))


(defprotocol Replicator
  (start [this])
  (stop  [this]))

(defn default-opts []
  {:init          init-dest-database
   :e->lookup-ref e->lookup-ref-default
   :poll-interval 100
   :start-t       nil})

(defn- get-start-t [conn]
  (let [db   (d/db conn)
        meta (d/entity db :datomic-replication/metadata)]
    (:datomic-replication/source-t meta)))

(defn replicator
  "Returns a replicator that copies transactions from source-conn to dest-conn.
  These can be actual connections or just the URIs as strings.

  Call `start`, passing the returned object, to begin replication.
  Call `stop` to stop replication.

  `opts` is a map that can have the the following keys (all optional):

  :init - function to initialize the destination database. Will be
  passed 2 arguments: [dest-conn tx-instant], where `tx-instant` is
  that of the first transaction to be replicated.

  :e->lookup-ref - Function to return a lookup-ref, given a db and an
  eid. The default returns [:db/ident <ident>] if the entity has an
  ident, or [:datomic-replication/source-eid <eid>] otherwise.
  
  :poll-interval - Number of milliseconds to wait before calling
  `tx-range` again after calling it and getting no transactions. This
  determines how frequently to poll when we are \"caught up\".
  
  :start-t - The `t` to start from. Default is nil, which means to
  start at the beginning of the source database's history, or at the
  last-replicated t, as stored in the destination database.
  "
  ([source-conn dest-conn]
     (replicator source-conn dest-conn nil))
  ([source-conn dest-conn opts]
     (let [opts          (merge (default-opts) opts)
           init          (:init opts)
           e->lookup-ref (:e->lookup-ref opts)
           source-conn   (if (string? source-conn)
                           (d/connect source-conn)
                           source-conn)
           dest-conn     (if (string? dest-conn)
                           (do
                             (d/create-database dest-conn)
                             (d/connect dest-conn))
                           dest-conn)
           start-t       (or (:start-t opts)
                             (get-start-t dest-conn))
           control-chan  (async/chan)
           [txs stopper] (transactions source-conn (assoc opts :start-t start-t))
           initialized?  (atom false)]
       (reify Replicator
         (start [this]
           (log/info "Starting replicator")
           (go-loop []
             (let [[tx ch] (async/alts! [txs control-chan])]
               (when (identical? ch txs)
                 (when-not @initialized?
                   (log/info "Initializing destination database")
                   (let [tx-instant (->> tx :t d/t->tx (d/entity (d/db source-conn)) :db/txInstant)]
                     (init dest-conn tx-instant))
                   (reset! initialized? true))
                 (try
                   (replicate-tx tx source-conn dest-conn e->lookup-ref)
                   (catch clojure.lang.ExceptionInfo e
                     (when (= :db.error/transaction-timeout (:db/error (ex-data e)))
                       ;; Transact timeout. Wait a bit and try again.
                       (log/warn "Transact timed out. Pausing.")
                       (<! (async/timeout 10000)))))
                 (recur)))))
         (stop [this]
           (log/info "Stopping replicator")
           (stopper)
           (async/put! control-chan :stop))))))
