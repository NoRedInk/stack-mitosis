(ns stack-mitosis.policy
  (:require [stack-mitosis.request :as r]
            [stack-mitosis.lookup :as lookup]
            [stack-mitosis.predict :as predict]
            [clojure.string :as str]))

(defn make-arn
  [db-id & {:keys [account-id region type]
            :or {account-id "*" region "*" type "db"}}]
  (str/join ":" ["arn:aws:rds" region account-id type db-id]))

(defmulti permissions
  "Calculate permissions required for a given operation.

      (permissions [instances action]) => [{:op _ :arn _} ...]"
  (fn [_ action] (get action :op)))

(defmethod permissions :shell-command
  [_ _]
  [])

(defmethod permissions :CreateDBInstanceReadReplica
  [instances action]
  ;; For create replica, use the ARN from the source database
  (let [source-id (r/source-id action)
        db-id (r/db-id action)
        source-arn (:DBInstanceArn (lookup/by-id instances source-id))
        target-arn (:DBInstanceArn (lookup/by-id (predict/predict instances action) db-id))]
    [{:op (:op action) :arn source-arn}
     {:op (:op action) :arn target-arn}
     {:op :AddTagsToResource :arn target-arn}]))

(defmethod permissions :default
  [instances action]
  (let [db-id (r/db-id action)]
    (if-let [instance (lookup/by-id instances db-id)]
      [{:op (:op action)
        :arn (:DBInstanceArn instance)}]
      ;; TODO handle ResourceName for ListTagsForResource
      [{:op (:op action)}])))

;; TODO possibly generate optional statement identifier?
;; TODO simplify action/resource to singular if only one value?
(defn allow [actions resources]
  {:Effect "Allow"
   :Action (mapv (partial str "rds") actions)
   :Resource resources})

(defn create-example []
  (allow [:CreateDBInstance :AddTagsToResource]
         [(make-arn "mitosis-*")]))

(defn globals []
  (allow [:DescribeDBInstances :ListTagsForResource]
         [(make-arn "*")]))

;; TODO breakup permissions per operation type with better granularity
;; ie Delete should only have permissions on old-, not temp- or current staging.
;; TODO tighten restrictions on DB OptionGroup (og), DB ParameterGroup (pg) and DB Subnet Group (subgrp)
;; these were listed as warnings in the policy editor so leaving wildcard for now
(defn generate [instances operations]
  (let [all-permissions
        (flatten
         (map permissions
              (reductions predict/predict instances operations)
              operations))]
    (for [[op ops] (group-by :op all-permissions)
          :let [arns (distinct (map :arn ops))]]
      (cond (= op :CreateDBInstanceReadReplica)
            ;; TODO account for AddTagsToResource on created instances
            (allow [op]
                   (into [(make-arn "*" :type "og")
                          (make-arn "*" :type "pg")
                          (make-arn "*" :type "subgrp")]
                         arns))
            (= op :ModifyDBInstance)
            ;; Give RebootInstance if apply ModifyDBInstance so that ApplyImmediately can reboot
            (allow [op :RebootDBInstance]
                   (into [(make-arn "*" :type "og")
                          (make-arn "*" :type "pg")
                          (make-arn "*" :type "subgrp")
                          (make-arn "*" :type "secgrp")]
                         arns))
            :else
            (allow [op] arns)))))

(defn policy [statements]
  {:Version "2012-10-17" :Statement statements})

(defn from-plan [instances operations]
  (policy (concat [(globals)] (generate instances operations))))

(comment
  (require '(clojure.data.json :as json))
  (json/pprint (policy [(globals) (create-example)])))
