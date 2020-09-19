(ns stack-mitosis.policy-test
  (:require [clojure.test :as t :refer [deftest is]]
            [stack-mitosis.operations :as op]
            [stack-mitosis.policy :as sut]
            [stack-mitosis.planner :as plan]))

(deftest permissions
  (let [instance {:DBInstanceIdentifier "foo" :DBInstanceArn "arn:aws:rds:us-east-1"}]
    (is (= {:op :DeleteDBInstance}
           (sut/permissions [] (op/delete "foo")))
        "only operation if instance is not found")
    (is (= {:op :DeleteDBInstance :arn "arn:aws:rds:us-east-1"}
           (sut/permissions [instance] (op/delete "foo")))
        "operation and arn if instance is found")
    (is (= {:op :DescribeDBInstances}
           (sut/permissions [] (op/describe)))
        "operation only if no database identifier in request")))

(deftest generate
  (let [instances [{:DBInstanceIdentifier "production" :ReadReplicaDBInstanceIdentifiers ["production-replica"]
                    :DBInstanceArn "production-arn"}
                   {:DBInstanceIdentifier "production-replica" :ReadReplicaSourceDBInstanceIdentifier "production"
                    :DBInstanceArn "production-replica-arn"}
                   {:DBInstanceIdentifier "staging" :ReadReplicaDBInstanceIdentifiers ["staging-replica"]
                    :DBInstanceArn "staging-arn"}
                   {:DBInstanceIdentifier "staging-replica" :ReadReplicaSourceDBInstanceIdentifier "staging"
                    :DBInstanceArn "staging-replica-arn"}]]
    (is (= {:effect "Allow"
            :action [:CreateDBInstanceReadReplica :PromoteReadReplica :ModifyDBInstance :DeleteDBInstance]
            ;; FIXME: note that create db, promote, and modify may have a different set of resource permissions from delete
            :resource []}
           (sut/generate instances (plan/replace-tree instances "production" "staging"))))))
