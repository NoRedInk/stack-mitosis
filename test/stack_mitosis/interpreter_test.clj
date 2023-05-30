(ns stack-mitosis.interpreter-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.tools.logging.test :as tlog]
            [stack-mitosis.interpreter :as sut]
            [stack-mitosis.rds-operations :as op]
            [cognitect.aws.client.api :as aws]))

(defn- eval-plan [ops]
  (tlog/with-log
    (let [capture (new java.io.StringWriter)]
      (binding [*out* capture]
        (let [result (sut/evaluate-plan nil ops)]
          {:output (str/split-lines (str capture))
           :logging (map :message (tlog/the-log))
           :last-result result})))))

(defn- mock-invoke
  [instances]
  (fn [& _] {:DBInstances instances}))

(deftest databases
  (with-redefs [aws/invoke (mock-invoke [{:DBInstanceIdentifier "a"}])]
    (is (= [{:DBInstanceIdentifier "a"}]
           (sut/databases identity))))
  (with-redefs [aws/invoke (mock-invoke [])]
    (is (= [] (sut/databases identity))))
  (with-redefs [aws/invoke (fn [& _] {})]
    (is (thrown-with-msg? java.lang.AssertionError #"Assert failed"
                          (sut/databases identity))))
  )

(deftest evaluate-plan
  (with-redefs [aws/invoke (mock-invoke [{:DBInstanceIdentifier "a"}])]
    (is (= {:output
            ["  exit: 1"]
            :logging
            ["Invoking {:op :shell-command, :request {:cmd \"false\"}}"
             "Executing [false] failed with status 1"]
            :last-result
            {:ErrorResponse "Executing [false] failed with status 1"}}
           (eval-plan [(op/shell-command "false")
                       (op/shell-command "true")]))
        "early exit")
    (is (= {:output
            ["1" "  exit: 0"
             "2" "  exit: 0"]
            :logging
            ["Invoking {:op :shell-command, :request {:cmd \"echo 1\"}}"
             "{:ok Executing [echo 1] succeeded}"
             "Invoking {:op :shell-command, :request {:cmd \"echo 2\"}}"
             "{:ok Executing [echo 2] succeeded}"]
            :last-result
            {:ok "Executing [echo 2] succeeded"}}
           (eval-plan [(op/shell-command "echo 1")
                       (op/shell-command "echo 2")]))
        "execute all")))
