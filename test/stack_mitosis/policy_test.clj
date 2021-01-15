(ns stack-mitosis.policy-test
  (:require [clojure.test :as t :refer [deftest is]]
            [stack-mitosis.operations :as op]
            [stack-mitosis.policy :as sut]
            [stack-mitosis.planner :as plan]))

(defn fake-arn [name]
  (str "arn:aws:rds:us-east-1:1234567:db:" name))

(defn example-instances []
  [{:DBInstanceIdentifier "production" :ReadReplicaDBInstanceIdentifiers ["production-replica"]
    :DBInstanceArn (fake-arn "production")}
   {:DBInstanceIdentifier "production-replica" :ReadReplicaSourceDBInstanceIdentifier "production"
    :DBInstanceArn (fake-arn "production-replica")}
   {:DBInstanceIdentifier "staging" :ReadReplicaDBInstanceIdentifiers ["staging-replica"]
    :DBInstanceArn (fake-arn "staging")}
   {:DBInstanceIdentifier "staging-replica" :ReadReplicaSourceDBInstanceIdentifier "staging"
    :DBInstanceArn (fake-arn "staging-replica")}])

(deftest permissions
  (let [instance {:DBInstanceIdentifier "foo" :DBInstanceArn (fake-arn "foo")}]
    (is (= {:op :DeleteDBInstance :arn (fake-arn "foo")}
           (sut/permissions [instance] (op/delete "foo")))
        "only operation if instance is not found")
    (is (= {:op :DeleteDBInstance :arn (fake-arn "foo")}
           (sut/permissions [instance] (op/delete "foo")))
        "operation and arn if instance is found")
    (is (= {:op :DescribeDBInstances}
           (sut/permissions [] (op/describe)))
        "operation only if no database identifier in request")))

;; TODO: how to incorporate permissions for ListTags and DescribeDBInstances
;; also how to create policy for example environment creation?
;; TODO: handle possible need for RebootInstance if renaming with ApplyImmediatly?
(deftest generate
  (is (= [(sut/allow [:CreateDBInstanceReadReplica]
                     (mapv fake-arn ["temp-staging" "temp-staging-replica"]))
          (sut/allow [:PromoteReadReplica]
                     [(fake-arn "temp-staging")])
          (sut/allow [:ModifyDBInstance]
                     (mapv fake-arn ["temp-staging" "temp-staging-replica" "staging-replica" "staging"]))
          (sut/allow [:DeleteDBInstance]
                     (mapv fake-arn ["old-staging-replica" "old-staging"]))]
         (sut/generate (example-instances)
                       (plan/replace-tree (example-instances) "production" "staging")))))
