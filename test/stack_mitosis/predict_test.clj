(ns stack-mitosis.predict-test
  (:require [stack-mitosis.predict :as p]
            [stack-mitosis.operations :as op]
            [clojure.test :refer :all]))

(deftest create
  (is (= [{:DBInstanceIdentifier "a"}]
         (p/predict [] (op/create {:DBInstanceIdentifier "a"}))))
  (is (= [{:DBInstanceIdentifier "a"}]
         (p/predict [] (op/create {:DBInstanceIdentifier "a" :MasterUsername "foo" :MasterUserPassword "bar"}))))
  (is (thrown-with-msg? java.lang.AssertionError #"complement"
                        (p/predict [{:DBInstanceIdentifier "a"}]
                                   (op/create {:DBInstanceIdentifier "a"})))))

(deftest modify
  (let [instances [{:DBInstanceIdentifier "a"}
                   {:DBInstanceIdentifier "b"}]]
    (is (= [{:DBInstanceIdentifier "a"}
            {:DBInstanceIdentifier "new-name"}]
           (p/predict instances (op/rename "b" "new-name"))))
    (is (= [{:DBInstanceIdentifier "a"}
            {:DBInstanceIdentifier "b" :MultiAZ true}]
           (p/predict instances
                      (op/modify "b" {:MultiAZ true}))))))

(deftest promote
  (let [instances [{:DBInstanceIdentifier "root"
                    :ReadReplicaDBInstanceIdentifiers ["leaf"]}
                   {:DBInstanceIdentifier "leaf"
                    :ReadReplicaSourceDBInstanceIdentifier "root"}]]
    (is (= [{:DBInstanceIdentifier "root"
             :ReadReplicaDBInstanceIdentifiers []}
            {:DBInstanceIdentifier "leaf"
             :BackupRetentionPeriod 1}]
           (p/predict instances
                      {:op :PromoteReadReplica
                       :request {:DBInstanceIdentifier "leaf"
                                 :BackupRetentionPeriod 1}})))
    (is (thrown-with-msg? java.lang.AssertionError #"ReadReplicaSourceDBInstanceIdentifier"
                          (p/predict instances (op/promote "root"))))))

(deftest create-replica
  (let [instances [{:DBInstanceIdentifier "root" :MultiAZ false}]]
    (is (= [{:DBInstanceIdentifier "root" :MultiAZ false
             :ReadReplicaDBInstanceIdentifiers ["replica"]}
            {:DBInstanceIdentifier "replica" :MultiAZ false :Port 123
             :ReadReplicaSourceDBInstanceIdentifier "root"}]
           (p/predict instances
                      {:op :CreateDBInstanceReadReplica
                       :request {:DBInstanceIdentifier "replica"
                                 :SourceDBInstanceIdentifier "root"
                                 :Port 123}})))
    (testing "propagate only *some* instance fields to replica"
      (let [root {:DBInstanceIdentifier "root" :BackupRetentionPeriod 1
                  :ReadReplicaDBInstanceIdentifiers ["other-clone"]
                  :Port 123}]
        (is (= [(update-in root [:ReadReplicaDBInstanceIdentifiers] conj "clone")
                {:DBInstanceIdentifier "clone" :ReadReplicaSourceDBInstanceIdentifier "root" :Port 123}]
               (p/predict [root] (op/create-replica "root" "clone"))))))))

(deftest delete
  (let [instances [{:DBInstanceIdentifier "a"}
                   {:DBInstanceIdentifier "b"}]]
    (is (= [{:DBInstanceIdentifier "a" :ReadReplicaDBInstanceIdentifiers []}]
           (p/predict instances (op/delete "b"))))))

(deftest state
  (let [instances [{:DBInstanceIdentifier "root"}]
        ops [(op/create-replica "root" "foo")
             (op/create-replica "foo" "beta")
             (op/create-replica "foo" "omega")
             (op/rename "foo" "alpha")
             (op/promote "alpha")]]
    (is (= [{:DBInstanceIdentifier "root" :ReadReplicaDBInstanceIdentifiers []}
            {:DBInstanceIdentifier "alpha" :ReadReplicaDBInstanceIdentifiers ["omega" "beta"]}
            {:DBInstanceIdentifier "beta" :ReadReplicaSourceDBInstanceIdentifier "alpha"}
            {:DBInstanceIdentifier "omega" :ReadReplicaSourceDBInstanceIdentifier "alpha"}]
           (p/state instances ops))))
  (let [instances [{:DBInstanceIdentifier "root"}]
        ops [(op/create-replica "root" "alpha")
             (op/create-replica "alpha" "beta")
             (op/promote "alpha")
             (op/delete "beta")]]
    (is (= [{:DBInstanceIdentifier "root" :ReadReplicaDBInstanceIdentifiers []}
            {:DBInstanceIdentifier "alpha" :ReadReplicaDBInstanceIdentifiers []}]
           (p/state instances ops)))))

(deftest shell-command
  (let [instances [{:DBInstanceIdentifier "root"}]]
    (is (= instances (p/predict instances (op/shell-command "restart"))))))
