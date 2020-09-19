(ns stack-mitosis.policy-test
  (:require [clojure.test :as t :refer [deftest is]]
            [stack-mitosis.operations :as op]
            [stack-mitosis.policy :as sut]))

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
