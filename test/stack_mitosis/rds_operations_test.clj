(ns stack-mitosis.rds-operations-test
  (:require [stack-mitosis.rds-operations :as op]
            [clojure.tools.logging.test :as test-log]
            [clojure.test :refer :all]))

(deftest blocking-operation
  (is (op/blocking-operation? (op/create-replica "bar" "foo")))
  (is (op/blocking-operation? (op/rename "rename-before" "rename-after")))
  (is (not (op/blocking-operation? (op/delete "foo")))))

(deftest transition-to
  (test-log/with-log
    (is (= :in-progress (op/transition-to {})))
    (is (test-log/logged? 'stack-mitosis.rds-operations :error
                          "Unknown or missing db instance status from {}")))
  (test-log/with-log
    (is (= :in-progress (op/transition-to {:DBInstanceStatus nil})))
    (is (test-log/logged? 'stack-mitosis.rds-operations :error
                          "Unknown or missing db instance status from {:DBInstanceStatus nil}")))
  (test-log/with-log
    (is (= :in-progress (op/transition-to {:DBInstanceStatus ""})))
    (is (test-log/logged? 'stack-mitosis.rds-operations :error
                          "Unknown or missing db instance status from {:DBInstanceStatus }")))
  (is (= :done (op/transition-to {:DBInstanceStatus "available"})))
  (is (= :failed (op/transition-to {:DBInstanceStatus "failed"})))
  (is (= :in-progress (op/transition-to {:DBInstanceStatus "modifying"}))))

(deftest completed?
  (let [not-found {:ErrorResponse
                   {:Error
                    {:Type "Sender",
                     :Code "DBInstanceNotFound",
                     :Message "DBInstance foo not found."},
                    :RequestId "e176cadd-9689-4119-9ea3-9762eddc965f"},
                   :ErrorResponseAttrs {:xmlns "http://rds.amazonaws.com/doc/2014-10-31/"},
                   :cognitect.anomalies/category :cognitect.anomalies/not-found}
        available {:DBInstances [{:DBInstanceStatus "available"}]}]
    (is (not (op/completed? not-found)))
    (is (op/completed? available))
    (is (not (op/missing? available)))
    (is (op/missing? not-found))))
