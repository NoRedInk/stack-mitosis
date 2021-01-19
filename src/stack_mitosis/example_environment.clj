(ns stack-mitosis.example-environment
  (:require [stack-mitosis.helpers :as helpers]
            [stack-mitosis.operations :as op]
            [stack-mitosis.planner :as plan]
            [stack-mitosis.policy :as policy]
            [stack-mitosis.predict :as predict]))

(def template
  {:DBInstanceClass "db.t2.micro"
   :Engine "postgres" ;;"mysql"
   :StorageType "gp2" ;; ie ssd storage
   :AllocatedStorage 5
   :PubliclyAccessible false
   :MasterUsername "root"})

(defn create [template]
  ;; mysql allows replicas of replicas, postgres does not
  ;; create & create replica of a fresh instance take ~6 minutes
  [(op/create (merge {:DBInstanceIdentifier "mitosis-prod"
                      :MasterUserPassword (helpers/generate-password)}
                     template))
   (op/create-replica "mitosis-prod" "mitosis-prod-replica")
   (op/create (merge {:DBInstanceIdentifier "mitosis-demo"
                      :MasterUserPassword (helpers/generate-password)}
                     template))
   (op/create-replica "mitosis-demo" "mitosis-demo-replica")
   #_(op/create-replica "mitosis-demo-replica" "mitosis-demo-alternate")

   (op/add-tags "mitosis-prod" [(op/kv "Service" "Mitosis") (op/kv "Env" "prod-mitosis")])
   (op/add-tags "mitosis-prod-replica" [(op/kv "Service" "Mitosis") (op/kv "Env" "prod-mitosis")])
   (op/add-tags "mitosis-demo" [(op/kv "Service" "Mitosis") (op/kv "Env" "demo-mitosis")])
   (op/add-tags "mitosis-demo-replica" [(op/kv "Service" "Mitosis") (op/kv "Env" "demo-mitosis")])

   ;; Changes *only* for the target environment to clone to verify they propagate
   (op/modify "mitosis-demo"
              {:DBPortNumber 5430
               :PreferredMaintenanceWindow "tue:07:02-tue:08:00"
               :PreferredBackupWindow "05:30-06:20"
               :EnableIAMDatabaseAuthentication true
               })
   (op/modify "mitosis-demo-replica"
              {:DBPortNumber 5431
               :PreferredMaintenanceWindow "mon:07:30-mon:08:00"
               :EnableIAMDatabaseAuthentication false
               ;; https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PerfInsights.html
               ;; disabled for mysql on db.t3.micro and other t2/t3 instance classes
               ;; Performance Insights not supported for this configuration, please disable this feature (mysql?)
               ;; :EnablePerformanceInsights true
               })
   ])

(defn destroy []
  (let [state (predict/state [] (create template))]
    (concat (plan/delete-tree state "mitosis-demo")
            (plan/delete-tree state "mitosis-prod"))))

(comment
  ;; Add fake arn to create template?
  (policy/generate [] (create template))
  ;; Fix this to actual return a functioning delete policy?
  (policy/generate (predict/state [] (create template)) (destroy)))
