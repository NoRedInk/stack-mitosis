(ns stack-mitosis.planner
  (:require [clojure.string :as str]
            [clojure.data]
            [stack-mitosis.helpers :as helpers
             :refer [bfs-tree-seq topological-sort update-if]]
            [stack-mitosis.lookup :as lookup]
            [stack-mitosis.operations :as op]
            [stack-mitosis.predict :as predict]
            [stack-mitosis.request :as r]))

(defn aliased [prefix name]
  (str prefix "-" name))

(defn transform-instance
  [alias-fn instance]
  (-> instance
      (update :DBInstanceIdentifier alias-fn)
      ;; what about children identifiers?
      (update-if [:ReadReplicaSourceDBInstanceIdentifier] alias-fn)))

(defn list-tree
  [instances root]
  (bfs-tree-seq (partial lookup/replicas instances) root))

(defn topo
  [instances ids]
  (->> (map #(set (lookup/replicas instances %)) ids)
       (zipmap ids)
       topological-sort))

;; postgres does not allow replica of replica, so need to promote before
;; replicating children
(defn copy-tree
  [instances source target alias-fn & {:keys [tags] :or {tags {}}}]
  (let [alias-tags (into {} (map (fn [[k v]] [(alias-fn k) v]) tags))
        [root & tree] (map (comp (partial transform-instance alias-fn)
                                 (partial lookup/by-id instances))
                           (list-tree instances target))
        root-id (:DBInstanceIdentifier root)
        root-attrs (lookup/clone-replica-attributes root (get alias-tags root-id))]
    (into [(op/create-replica source root-id root-attrs)
           (op/promote root-id)
           (op/enable-backups root-id)] ;; postgres only allows backups after promotion
          (mapcat
           (fn [instance]
             (into [(op/create-replica (:ReadReplicaSourceDBInstanceIdentifier instance)
                                       (:DBInstanceIdentifier instance)
                                       (lookup/clone-replica-attributes instance
                                                                        (get alias-tags (:DBInstanceIdentifier instance))))]
                   ;; enable-backups for any replicas with children
                   (when (seq (:ReadReplicaDBInstanceIdentifiers instance))
                     [(op/enable-backups (:DBInstanceIdentifier instance))])))
           tree))))

(defn rename-tree
  [instances source alias-fn]
  (let [tree (topo instances (list-tree instances source))]
    (map op/rename tree (map alias-fn tree))))

(defn delete-tree
  [instances root]
  (->> (list-tree instances root)
       (topo instances)
       (map op/delete)))

(defn replace-tree
  [instances source target & {:keys [restart tags] :or {tags []}}]
  ;; actions in copy, rename & delete change the local instances db, so use
  ;; predict to update that db for calculating next set of operations by
  ;; applying computation thus far to the initial instances
  ;; TODO something something sequence monad
  (let [copy (copy-tree instances source target (partial aliased "temp"))

        a (predict/state instances copy)
        rename-old (rename-tree a target (partial aliased "old"))

        b (predict/state instances (concat copy rename-old))
        rename-temp (rename-tree b (aliased "temp" target) #(str/replace % "temp-" ""))

        restart-cmds (concat tags (when restart [(op/shell-command restart)]))

        c (predict/state instances (concat copy rename-old rename-temp restart-cmds))
        delete (delete-tree c (aliased "old" target))]
    (concat copy rename-old rename-temp restart-cmds delete)))


(defn duplicate-instance [id]
  (format "Instance '%s' already exists" id))

(defn promoted-instance [id]
  (format "Instance '%s' already promoted to top-level." id))

(defn no-changes [id]
  (format "Instance '%s' already applied modifications." id))

(defn attempt
  "filter for actions that have already happened and hydrate tag operations with correct ARN"
  [instances {:keys [op] :as action}]
  (cond
    (and (= op :CreateDBInstance)
         (lookup/by-id instances (r/db-id action)))
    [:skip (duplicate-instance (r/db-id action))]
    ;; catch error if replica has incorrect parent?
    (and (= op :CreateDBInstanceReadReplica)
         (lookup/by-id instances (r/db-id action)))
    [:skip (duplicate-instance (r/db-id action))]
    (and (= op :PromoteReadReplica)
         (not (:ReadReplicaSourceDBInstanceIdentifier (lookup/by-id instances (r/db-id action)))))
    [:skip (promoted-instance (r/db-id action))]
    (and (= op :ModifyDBInstance)
         (let [id (r/db-id action)
               current (lookup/by-id instances id)
               predicted (lookup/by-id (predict/state instances [action]) id)
               [diff-a diff-b _] (clojure.data/diff current predicted)]
           (and (empty? diff-a) (empty? diff-b))))
    [:skip (no-changes (r/db-id action))]
    ;; translate tags from a instance identifier to the new ARN
    (= op :AddTagsToResource)
    (let [{:keys [ResourceName Tags]} (:request action)]
      (if-let [db-arn (:DBInstanceArn (lookup/by-id instances ResourceName))]
        [:ok (op/add-tags db-arn Tags)]
        ;; TODO better messaging on skip action, instance is missing
        [:skip action]))
    :else [:ok action]))
