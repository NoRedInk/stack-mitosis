(ns stack-mitosis.policy
  (:require [stack-mitosis.request :as r]
            [stack-mitosis.lookup :as lookup]))

(defn permissions [instances action]
  (if-let [db-id (r/db-id action)]
    (if-let [instance (lookup/by-id instances db-id)]
      {:op (:op action)
       :arn (:DBInstanceArn instance)}
      {:op (:op action)})
    ;; TODO handle ResourceName for ListTagsForResource
    ;; TODO Exclude shell command, and include top level describe?
    {:op (:op action)}))

(defn generate [instances operations]
  (let [all-permissions (map (partial permissions instances) operations)
        resources (group-by :op all-permissions)]
    {:effect "Allow"
     :action (keys resources)
     :resource (distinct (mapcat :arn resources))}))
