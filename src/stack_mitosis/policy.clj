(ns stack-mitosis.policy
  (:require [stack-mitosis.request :as r]
            [stack-mitosis.lookup :as lookup]
            [stack-mitosis.predict :as predict]))

(defn make-wildcard-arn [db-id]
  (str "arn:aws:rds:*:*:db:" db-id))

(defn permissions [instances action]
  (if-let [db-id (r/db-id action)]
    (if-let [instance (lookup/by-id instances db-id)]
      {:op (:op action)
       :arn (:DBInstanceArn instance)}
      ;; FIXME: this is really gross
      ;; For create replica cases we don't yet have an instance with an ARN for
      ;; the newly created id, so we use predict to look ahead a step and use
      ;; the ARN from the newly created instance.
      (if-let [predicted (lookup/by-id (predict/predict instances action) db-id)]
        {:op (:op action)
         :arn (:DBInstanceArn predicted)}
        ;; fallback to wildcard if we don't recognize
        ;; This probably should never happen, can we ensure this and drop this
        ;; case OR should this be a nil arn case?
        {:op (:op action)
         :arn (make-wildcard-arn db-id)}))
    ;; TODO handle ResourceName for ListTagsForResource
    ;; TODO Exclude shell command, and include top level describe?
    {:op (:op action)}))

;; TODO possibly generate optional statement identifier?
;; TODO simplify action/resource to singular if only one value?
(defn allow [actions resources]
  {:effect "Allow"
   :action actions
   :resource resources})

(defn globals []
  (allow [:DescribeDBInstances :ListTagsForResource] ["arn:aws:rds:*"]))

;; TODO breakup permissions per operation type with better granularity
;; ie Delete should only have permissions on old-, not temp- or current staging.
(defn generate [instances operations]
  (let [all-permissions
        (map permissions
             (reductions predict/predict instances operations)
             operations)]
    (for [[op ops] (group-by :op all-permissions)]
      (allow [op] (distinct (map :arn ops))))))
