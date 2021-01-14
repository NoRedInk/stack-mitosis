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
      (if-let [predicted (lookup/by-id (predict/predict instances action) db-id)]
        {:op (:op action)
         :arn (:DBInstanceArn predicted)}
        ;; fallback to wildcard if we don't recognize
        {:op (:op action)
         :arn (make-wildcard-arn db-id)}))
    ;; TODO handle ResourceName for ListTagsForResource
    ;; TODO Exclude shell command, and include top level describe?
    {:op (:op action)}))

(defn generate [instances operations]
  (let [all-permissions
        (map permissions
             (reductions predict/predict instances operations)
             operations)

        resources (group-by :op all-permissions)]
    {:effect "Allow"
     :action (keys resources)
     :resource (distinct (map :arn (flatten (vals resources))))}))
