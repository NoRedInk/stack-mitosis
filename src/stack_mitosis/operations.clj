(ns stack-mitosis.operations
  (:require [clojure.tools.logging :as log]))

(defn delete
  [id]
  {:op :DeleteDBInstance
   :request {:DBInstanceIdentifier id
             :SkipFinalSnapshot true}})

(defn modify
  [id options]
  {:op :ModifyDBInstance
   :request
   (merge {:DBInstanceIdentifier id
           :ApplyImmediately true}
          options)})

(defn rename
  [old new]
  (modify old {:NewDBInstanceIdentifier new}))

(defn enable-backups
  ([id]
   (enable-backups id {}))
  ([id attributes]
   (modify id (merge attributes {:BackupRetentionPeriod 1}))))

(defn create
  [options]
  {:op :CreateDBInstance
   :request options})

(defn create-replica
  ([source replica] (create-replica source replica {}))
  ([source replica attributes]
   {:op :CreateDBInstanceReadReplica
    ;; when creating a replica in the same region as the primary, you must not specify the kms key id.
    ;; the replica will inherit the same key as the primary automatically
    :request (merge (dissoc attributes :KmsKeyId)
                    {:SourceDBInstanceIdentifier source
                     :DBInstanceIdentifier replica})}))

(defn restore-snapshot
  ([snapshot-id source target] (restore-snapshot snapshot-id source target {}))
  ([snapshot-id source target attributes]
   {:op :RestoreDBInstanceFromDBSnapshot
    :request (merge attributes
                    {:DBSnapshotIdentifier snapshot-id
                     :DBInstanceIdentifier target})
    :meta {:SourceDBInstance source}}))

(defn list-snapshots
  ([target]
   {:op :DescribeDBSnapshots
    :request {:DBInstanceIdentifier target}}))

(defn promote
  [id]
  {:op :PromoteReadReplica
   :request {:DBInstanceIdentifier id}})

(defn describe
  ([] {:op :DescribeDBInstances})
  ([id]
   {:op :DescribeDBInstances
    :request {:DBInstanceIdentifier id}}))

(defn tags [db-arn]
  {:op :ListTagsForResource
   :request {:ResourceName db-arn}})

(defn add-tags [db-arn tags]
  {:op :AddTagsToResource
   :request {:ResourceName db-arn
             :Tags tags}})

(defn kv [key value]
  {:Key key :Value value})

(defn shell-command [cmd]
  {:op :shell-command
   :request {:cmd cmd}})

(defn blocking-operation?
  [action]
  (contains? #{:CreateDBInstance :CreateDBInstanceReadReplica
               :PromoteReadReplica :ModifyDBInstance :RestoreDBInstanceFromDBSnapshot} (:op action)))

(defn transition-to
  "Maps current rds status to in-progress, failed or done

  From https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.DBInstance.Status.html"
  [state]
  (condp contains? (:DBInstanceStatus state)
    #{"backing-up" "backtracking" "configuring-enhanced-monitoring"
      "configuring-iam-database-auth" "configuring-log-exports"
      "converting-to-vpc" "creating" "deleting" "maintenance" "modifying"
      "moving-to-vpc" "rebooting" "renaming" "resetting-master-credentials"
      "starting" "stopping" "storage-optimization" "upgrading"}
    :in-progress

    #{"failed" "inaccessible-encryption-credentials" "incompatible-credentials"
      "incompatible-network" "incompatible-option-group"
      "incompatible-parameters" "incompatible-restore" "restore-error"
      "storage-full"}
    :failed

    #{"stopped" "available"}
    :done

    (do
      (log/error "Unknown or missing db instance status from" state)
      :in-progress)
    ))

(defn completed?
  [described-instances]
  (if (:ErrorResponse described-instances)
    false
    (contains? #{:done :failed} (transition-to (first (:DBInstances described-instances))))))

(defn missing?
  [described-instances]
  (= :cognitect.anomalies/not-found (:cognitect.anomalies/category described-instances)))
