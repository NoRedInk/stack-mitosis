(ns stack-mitosis.policy-test
  (:require [clojure.test :as t :refer [deftest is]]
            [stack-mitosis.operations :as op]
            [stack-mitosis.policy :as sut]
            [stack-mitosis.planner :as plan]))

(defn make-arn [name]
  (str "arn:aws:rds:us-east-1:1234567:db:" name))

(defn example-instances []
  [{:DBInstanceIdentifier "production" :ReadReplicaDBInstanceIdentifiers ["production-replica"]
    :DBInstanceArn (make-arn "production")}
   {:DBInstanceIdentifier "production-replica" :ReadReplicaSourceDBInstanceIdentifier "production"
    :DBInstanceArn (make-arn "production-replica")}
   {:DBInstanceIdentifier "staging" :ReadReplicaDBInstanceIdentifiers ["staging-replica"]
    :DBInstanceArn (make-arn "staging")}
   {:DBInstanceIdentifier "staging-replica" :ReadReplicaSourceDBInstanceIdentifier "staging"
    :DBInstanceArn (make-arn "staging-replica")}])

(deftest permissions
  (let [instance {:DBInstanceIdentifier "foo" :DBInstanceArn (make-arn "foo")}]
    (is (= {:op :DeleteDBInstance :arn (make-arn "foo")}
           (sut/permissions [instance] (op/delete "foo")))
        "only operation if instance is not found")
    (is (= {:op :DeleteDBInstance :arn (make-arn "foo")}
           (sut/permissions [instance] (op/delete "foo")))
        "operation and arn if instance is found")
    (is (= {:op :DescribeDBInstances}
           (sut/permissions [] (op/describe)))
        "operation only if no database identifier in request")))

(deftest generate
  (is (= {:effect "Allow"
          :action [:CreateDBInstanceReadReplica :PromoteReadReplica :ModifyDBInstance :DeleteDBInstance]
          ;; FIXME: note that create db, promote, and modify may have a different set of resource permissions from delete
          :resource [(make-arn "temp-staging")
                     (make-arn "temp-staging-replica")
                     (make-arn "staging-replica")
                     (make-arn "staging")
                     (make-arn "old-staging-replica")
                     (make-arn "old-staging")]}
         (sut/generate (example-instances)
                       (plan/replace-tree (example-instances) "production" "staging")))))
