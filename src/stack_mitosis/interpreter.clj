(ns stack-mitosis.interpreter
  (:require [clojure.data]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cognitect.aws.client.api :as aws]
            [stack-mitosis.example-environment :as example]
            [stack-mitosis.lookup :as lookup]
            [stack-mitosis.operations :as op]
            [stack-mitosis.planner :as plan]
            [stack-mitosis.predict :as predict]
            [stack-mitosis.request :as r]
            [stack-mitosis.shell :as shell]
            [stack-mitosis.sudo :as sudo]
            [stack-mitosis.wait :as wait]))

;; TODO: thread this client to all that use it
(defn client
  []
  (aws/client {:api :rds :credentials-provider (sudo/provider)}))

(defn databases
  [rds]
  {:post [(seq %)]}
  (:DBInstances (aws/invoke rds {:op :DescribeDBInstances})))

(defn list-tags
  "Mapping of db-id to tags list for each instance in a tree."
  [rds instances target]
  (let [tree (plan/list-tree instances target)]
    (->> tree
         (map (fn [resource-name]
                (let [instance (lookup/by-id instances resource-name)
                      arn (:DBInstanceArn instance)
                      db-id (:DBInstanceIdentifier instance)]
                  [db-id (:TagList (aws/invoke rds (op/tags arn)))])))
         (into {}))))

(defn clone-tags
  "List of add-tag operations for each instance in a tree by DBInstanceIdentifier."
  [rds instances target]
  (map (fn [[db-id tags]]
         ;; we store the operation as an add-tags on instance-id, so it can
         ;; be translated to arn at time of invocation.
         (op/add-tags db-id tags))
       (list-tags rds instances target)))

(defn describe
  [rds id]
  (aws/invoke rds (op/describe id)))

(defn- invoke!
  [rds action]
  (if-let [cmd (and (= :shell-command (:op action))
                    (get-in action [:request :cmd]))]
    (shell/bash cmd)
    (aws/invoke rds action)))

(defn- wait-for-action
  [rds action]
  (let [id (r/db-id action)
        [result-id completed-fn]
        (if-let [new-id (r/new-id action)]
          [new-id #(and (op/missing? (describe rds id))
                        (op/completed? (describe rds new-id)))]
          [id #(op/completed? (describe rds id))])
        started (. System (nanoTime))
        ret (wait/poll-until completed-fn {:delay 60000 :max-attempts 60})
        msecs (/ (double (- (. System (nanoTime)) started)) 1000000.0)
        status (-> (describe rds result-id) :DBInstances first :DBInstanceStatus)
        msg (format "Completed after : %.2fs with status %s" (/ msecs 1000) status)]
    (log/info msg)
    ret))

(defn interpret [rds action]
  (log/infof "Invoking %s" action)
  (let [[plan action] (plan/attempt (databases rds) action)]
    (if (= plan :skip)
      (log/infof "Skipping: %s" action)
      (let [result (invoke! rds action)]
        (if-let [error-resp (:ErrorResponse result)]
          (do
            (log/error error-resp)
            result)
          (do
            (log/info result)
            (when (op/blocking-operation? action)
              (wait-for-action rds action))
            result))))))

(defn evaluate-plan
  [rds operations]
  (loop [[action & ops] operations]
    (let [result (interpret rds action)]
      (cond (empty? ops) ;; all operations complete
            result
            (:ErrorResponse result) ;; exit early on failure
            result
            :else
            (recur ops)))))

(defn check-plan
  "Check plan against current state before evaluating."
  [state operations]
  (map plan/attempt (reductions predict/predict state operations) operations))

(comment
  (sudo/sudo-provider (sudo/load-role "resources/role.edn"))
  (def rds (client))
  (-> (predict/state [] (example/create example/template))
      (plan/replace-tree "mitosis-prod" "mitosis-demo"))

  (interpret rds (op/shell-command "echo restart"))
  (evaluate-plan rds [(op/shell-command "true") (op/shell-command "false")
                      (op/shell-command "true")])

  ;; check plan
  (let [state (databases rds)]
    (check-plan state (plan/replace-tree state "mitosis-prod" "mitosis-demo")))

  ;; create a copy of mitosis-prod tree
  (let [state (databases rds)]
    (plan/copy-tree state "mitosis-prod" "mitosis-demo"
                    #(str/replace % "demo" "temp")
                    :tags {"mitosis-demo-replica" [(op/kv "a" "b")]}))

  ;; TODO: move attempt into planning, ie we should skip steps that already happen even in planning
  ;; change wait mechanics to poll all?
  ;; improve wait mechanics for rename and other modify actions

  (filter #(re-find #"mitosis" %) (map :DBInstanceIdentifier (databases rds)))
  (time (evaluate-plan rds (example/create example/template)))
  (time (evaluate-plan rds
                       (let [instances (databases rds)
                             tags (list-tags rds instances "mitosis-demo")]
                         (plan/replace-tree instances "mitosis-prod" "mitosis-demo"
                                            :tags tags))))
  (time (evaluate-plan rds (example/destroy)))
  )

(comment
  (keys (aws/ops rds))
  (aws/doc rds :CreateDBInstance) ;; for testing
  (aws/doc rds :DescribeDBInstances)
  (aws/doc rds :CreateDBInstanceReadReplica)
  (aws/doc rds :PromoteReadReplica)
  (aws/doc rds :ModifyDBInstance)
  (aws/doc rds :DeleteDBInstance)
  (aws/doc rds :ListTagsForResource)
  (aws/doc rds :AddTagsToResource)

  (def instances (databases rds))

  (map #(select-keys % [:DBInstanceIdentifier :DBInstanceStatus]) (databases rds))
  (filter #(re-find #"mysql" (:Engine %)) instances)

  (map (fn [{:keys [DBInstanceIdentifier
                   ReadReplicaDBInstanceIdentifiers
                   ReadReplicaSourceDBInstanceIdentifier
                   DBInstanceArn]}]
         {:id DBInstanceIdentifier
          :arn DBInstanceArn
          :source ReadReplicaSourceDBInstanceIdentifier
          :replicas ReadReplicaDBInstanceIdentifiers})
       instances)

  (def example-id (:DBInstanceIdentifier (rand-nth instances)))
  (->> example-id op/describe (aws/invoke rds) :DBInstances first)
  (wait/poll-until #(op/completed? (aws/invoke rds (op/describe example-id)))
                   {:delay 100 :max-attempts 5})

  (:TagList (aws/invoke rds (op/tags (:DBInstanceArn (last instances)))))

  (list-tags rds instances "mitosis-demo")
  (time (evaluate-plan rds [(op/add-tags "mitosis-demo" [(op/kv "Service" "Mitosis")])]))

  (let [instances (databases rds)]
    (clojure.data/diff
     (lookup/by-id instances "mitosis-prod")
     (lookup/by-id instances "mitosis-demo")))

  ;; note that *changing* security groups uses :VpcSecurityGroupIds, not :VpcSecurityGroups
  (time (evaluate-plan rds [(op/modify "mitosis-demo" {:VpcSecurityGroupIds ["sg-abcdef"]})]))
  )
