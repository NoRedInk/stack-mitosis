(ns stack-mitosis.predict
  (:require [stack-mitosis.lookup :as lookup]
            [stack-mitosis.request :as r]
            [clojure.string :as str]))

(defn attach
  [db child-id]
  (update db :ReadReplicaDBInstanceIdentifiers
          conj child-id))

(defn detach
  [db child-id]
  (update db :ReadReplicaDBInstanceIdentifiers
          (partial remove #(= % child-id))))

(defn predict-arn
  "Replace the database identifier at the end of the ARN with a new value.

  See https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_Tagging.ARN.html
  for documentation on RDS specific ARN generation."
  [instance arn parent-id child-id]
  (if arn
    (assoc instance :DBInstanceArn
           (str/replace arn (re-pattern (str ":" parent-id "$")) (str ":" child-id)))
    instance))

(defmulti predict
  "Predict contents of instances db after applying operation to instances.

      (predict [instances op] _) => instances'
  "
  (fn [_ op] (get op :op)))

(defmethod predict :shell-command
  [instances _]
  ;; no-op identity
  instances)

(defmethod predict :AddTagsToResource
  [instances _]
  ;; no-op identity to instances (tags are set as side-effect)
  instances)

(defmethod predict :CreateDBInstance
  [instances op]
  {:pre [((complement lookup/exists?) instances (r/db-id op))]
   :post [(lookup/exists? % (r/db-id op))]}
  (conj instances (dissoc (:request op) :MasterUsername :MasterUserPassword)))

(defmethod predict :CreateDBInstanceReadReplica
  [instances op]
  {:pre [(lookup/exists? instances (r/source-id op))]
   :post [(lookup/exists? % (r/db-id op))]}
  (let [parent (r/source-id op)
        child (r/db-id op)
        source (lookup/by-id instances parent)]
    (conj (update instances (lookup/position instances parent) attach child)
          (-> source
              (merge (dissoc (:request op) :SourceDBInstanceIdentifier :ApplyImmediately))
              (assoc :ReadReplicaSourceDBInstanceIdentifier parent)
              ;; remove sources replica list for new replica, and reset backup
              ;; retention to match what AWS does.
              (dissoc :ReadReplicaDBInstanceIdentifiers :BackupRetentionPeriod)
              (predict-arn (:DBInstanceArn source) parent child)))))

(defmethod predict :PromoteReadReplica
  [instances op]
  {:pre [(lookup/exists? instances (r/db-id op))]}
  (if-not (:ReadReplicaSourceDBInstanceIdentifier (lookup/by-id instances (r/db-id op)))
    instances
    (let [child (r/db-id op)
          parent (lookup/parent instances child)]
      (letfn [(promote [db]
                (merge (dissoc db :ReadReplicaSourceDBInstanceIdentifier)
                       (dissoc (:request op) :DBInstanceIdentifier :ApplyImmediately)))]
        (-> instances
            (update (lookup/position instances parent) detach child)
            (update (lookup/position instances child) promote))))))

(defmethod predict :ModifyDBInstance
  [instances op]
  {:pre [(lookup/exists? instances (r/db-id op))]}
  (let [current-id (r/db-id op)
        new-id (r/new-id op)
        parent (lookup/parent instances current-id)]
    (letfn [(new-name [db]
              (merge (if new-id
                       (-> db
                           (assoc :DBInstanceIdentifier new-id)
                           (predict-arn (:DBInstanceArn db) current-id new-id))
                       db)
                     ;; merge in everything else in request
                     (dissoc (:request op)
                             :NewDBInstanceIdentifier :DBInstanceIdentifier :ApplyImmediately)))
            (rename-refs [db]
              (cond (and new-id (= (:ReadReplicaSourceDBInstanceIdentifier db) current-id))
                    ;; update children
                    (assoc db :ReadReplicaSourceDBInstanceIdentifier new-id)

                    (and new-id (= (:DBInstanceIdentifier db) parent))
                    (attach (detach db current-id) new-id)
                    :else db))]
      (mapv rename-refs
            (update instances (lookup/position instances current-id) new-name)))))

(defmethod predict :DeleteDBInstance
  [instances op]
  {:pre [(lookup/exists? instances (r/db-id op))]
   :post [(not (lookup/exists? % (r/db-id op)))]}
  (let [db-id (r/db-id op)]
    (->> instances
         (remove #(= (:DBInstanceIdentifier %) db-id))
         ;; may need to account for if delete is allowed if db has replicas
         (mapv #(detach % db-id)))))

(defn state [instances operations]
  (reduce predict instances operations))
