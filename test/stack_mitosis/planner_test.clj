(ns stack-mitosis.planner-test
  (:require [clojure.test :refer :all]
            [stack-mitosis.planner :as plan]
            [stack-mitosis.rds-operations :as op]
            [stack-mitosis.lookup :as lookup]))

(deftest list-tree
  (let [a {:DBInstanceIdentifier :a :ReadReplicaDBInstanceIdentifiers [:b]}
        b {:DBInstanceIdentifier :b :ReadReplicaDBInstanceIdentifiers [:c]}
        c {:DBInstanceIdentifier :c :ReadReplicaDBInstanceIdentifiers []}]
    (is (= [:a :b :c] (plan/list-tree [a b c] :a))))
  (let [a {:DBInstanceIdentifier :a :ReadReplicaDBInstanceIdentifiers [:b :c]}
        b {:DBInstanceIdentifier :b :ReadReplicaDBInstanceIdentifiers [:d]}
        c {:DBInstanceIdentifier :c :ReadReplicaDBInstanceIdentifiers []}
        d {:DBInstanceIdentifier :d :ReadReplicaDBInstanceIdentifiers []}]
    (is (= [:a :b :c :d] (plan/list-tree [a b c d] :a)))))

(deftest copy-tree
  (let [instances [{:DBInstanceIdentifier "source" :Iops 1000}
                   {:DBInstanceIdentifier "target" :ReadReplicaDBInstanceIdentifiers ["a" "b"] :Iops 500}
                   {:DBInstanceIdentifier "a" :ReadReplicaDBInstanceIdentifiers ["c"]
                    :ReadReplicaSourceDBInstanceIdentifier "target"}
                   {:DBInstanceIdentifier "b" :ReadReplicaSourceDBInstanceIdentifier "target"}
                   {:DBInstanceIdentifier "c" :ReadReplicaSourceDBInstanceIdentifier "b"}]
        instances-different-vpc [{:DBInstanceIdentifier "source" :Iops 1000, :DBSubnetGroup {:VpcId "v1"}}
                                 {:DBInstanceIdentifier "target" :ReadReplicaDBInstanceIdentifiers ["a" "b"] :Iops 500}
                                 {:DBInstanceIdentifier "a" :ReadReplicaDBInstanceIdentifiers ["c"]
                                  :ReadReplicaSourceDBInstanceIdentifier "target"}
                                 {:DBInstanceIdentifier "b" :ReadReplicaSourceDBInstanceIdentifier "target"}
                                 {:DBInstanceIdentifier "c" :ReadReplicaSourceDBInstanceIdentifier "b"}]
        snapshot-id "rds:source-snapshot-2021-03-17"
        tags-target [(op/kv "k" "target")]
        tags-b [(op/kv "k" "b")]]
    (is (= [(op/create-replica "source" "temp-target" {:Iops 500 :Tags tags-target})
            (op/promote "temp-target")
            (op/enable-backups "temp-target")
            (op/create-replica "temp-target" "temp-a")
            (op/modify "temp-a" {:BackupRetentionPeriod 1})
            (op/create-replica "temp-target" "temp-b" {:Tags tags-b})
            (op/modify "temp-b" {})
            (op/create-replica "temp-b" "temp-c")
            (op/modify "temp-c" {})]
           (plan/copy-tree instances "source" nil "target"
                           (partial plan/aliased "temp")
                           :tags {"target" tags-target
                                  "b" tags-b})))
    (is (= [(op/restore-snapshot snapshot-id
                                 (lookup/by-id instances-different-vpc "source")
                                 "temp-target" {:Iops 500 :Tags tags-target})
            (op/enable-backups "temp-target")
            (op/create-replica "temp-target" "temp-a")
            (op/modify "temp-a" {:BackupRetentionPeriod 1})
            (op/create-replica "temp-target" "temp-b" {:Tags tags-b})
            (op/modify "temp-b" {})
            (op/create-replica "temp-b" "temp-c")
            (op/modify "temp-c" {})]
           (plan/copy-tree instances-different-vpc "source" snapshot-id "target"
                           (partial plan/aliased "temp")
                           :tags {"target" tags-target
                                  "b" tags-b})))))

(deftest rename-tree
  (let [instances [{:DBInstanceIdentifier "root" :ReadReplicaDBInstanceIdentifiers ["a" "b"]}
                   {:DBInstanceIdentifier "a" :ReadReplicaDBInstanceIdentifiers ["c"]}
                   {:DBInstanceIdentifier "b"}
                   {:DBInstanceIdentifier "c"}]]
    (is (= [(op/rename "b" "temp-b")
            (op/rename "c" "temp-c")
            (op/rename "a" "temp-a")
            (op/rename "root" "temp-root")]
           (plan/rename-tree instances "root" (partial plan/aliased "temp"))))))

(deftest delete-tree
  (let [instances [{:DBInstanceIdentifier "source"}
                   {:DBInstanceIdentifier "target" :ReadReplicaDBInstanceIdentifiers ["a" "b"]}
                   {:DBInstanceIdentifier "a" :ReadReplicaDBInstanceIdentifiers ["c"]
                    :ReadReplicaSourceDBInstanceIdentifier "target"}
                   {:DBInstanceIdentifier "b" :ReadReplicaSourceDBInstanceIdentifier "target"}
                   {:DBInstanceIdentifier "c" :ReadReplicaSourceDBInstanceIdentifier "b"}]]
    (is (= [(op/delete "b")
            (op/delete "c")
            (op/delete "a")
            (op/delete "target")]
           (plan/delete-tree instances "target")))))

(deftest replace-tree
  (let [instances
        [{:DBInstanceIdentifier "production"}
         {:DBInstanceIdentifier "staging" :ReadReplicaDBInstanceIdentifiers ["staging-replica"]}
         {:DBInstanceIdentifier "staging-replica" :ReadReplicaSourceDBInstanceIdentifier "staging"}]
        snapshot-id "rds:production-2015-10-21"
        tags [(op/kv "Env" "Staging")]]
    (is (= [(op/create-replica "production" "temp-staging")
            (op/promote "temp-staging")
            (op/enable-backups "temp-staging" {})
            (op/create-replica "temp-staging" "temp-staging-replica")
            (op/modify "temp-staging-replica" {})
            (op/rename "staging-replica" "old-staging-replica")
            (op/rename "staging" "old-staging")
            (op/rename "temp-staging-replica" "staging-replica")
            (op/rename "temp-staging" "staging")
            (op/delete "old-staging-replica")
            (op/delete "old-staging")]
           (plan/replace-tree instances "production" nil "staging")))

    (is (= [(op/create-replica "production" "temp-staging")
            (op/promote "temp-staging")
            (op/enable-backups "temp-staging"
                               {:PreferredMaintenanceWindow "tue:02:00-tue:03:00"})
            (op/create-replica "temp-staging" "temp-staging-replica")
            (op/modify "temp-staging-replica"
                       {:PreferredMaintenanceWindow "tue:03:00-tue:04:00"})
            (op/rename "staging-replica" "old-staging-replica")
            (op/rename "staging" "old-staging")
            (op/rename "temp-staging-replica" "staging-replica")
            (op/rename "temp-staging" "staging")
            (op/delete "old-staging-replica")
            (op/delete "old-staging")]
           (plan/replace-tree
            [{:DBInstanceIdentifier "production" :ReadReplicaDBInstanceIdentifiers ["production-replica"]
              :PreferredMaintenanceWindow "tue:01:00-tue:02:00"}
             {:DBInstanceIdentifier "production-replica" :ReadReplicaSourceDBInstanceIdentifier "production"
              :PreferredMaintenanceWindow "tue:01:00-tue:02:00"}
             {:DBInstanceIdentifier "staging" :ReadReplicaDBInstanceIdentifiers ["staging-replica"]
              :PreferredMaintenanceWindow "tue:02:00-tue:03:00"}
             {:DBInstanceIdentifier "staging-replica" :ReadReplicaSourceDBInstanceIdentifier "staging"
              :PreferredMaintenanceWindow "tue:03:00-tue:04:00"}]
            "production" nil "staging")))

    (is (= [(op/restore-snapshot snapshot-id {:DBInstanceIdentifier "production"
                                              :DBInstanceArn "arn:db:production"
                                              :PreferredMaintenanceWindow "tue:01:00-tue:02:00"
                                              :ReadReplicaDBInstanceIdentifiers ["production-replica"]
                                              :DBSubnetGroup {:VpcId "v1"}} "temp-staging")
            (op/enable-backups "temp-staging"
                               {:PreferredMaintenanceWindow "tue:02:00-tue:03:00" :MonitoringInterval 60})
            (op/create-replica "temp-staging" "temp-staging-replica")
            (op/modify "temp-staging-replica"
                       {:PreferredMaintenanceWindow "tue:03:00-tue:04:00"})
            (op/rename "staging-replica" "old-staging-replica")
            (op/rename "staging" "old-staging")
            (op/rename "temp-staging-replica" "staging-replica")
            (op/rename "temp-staging" "staging")
            (op/delete "old-staging-replica")
            (op/delete "old-staging")]
           (plan/replace-tree
            [{:DBInstanceIdentifier "production" :DBInstanceArn "arn:db:production"
              :ReadReplicaDBInstanceIdentifiers ["production-replica"]
              :PreferredMaintenanceWindow "tue:01:00-tue:02:00" :DBSubnetGroup {:VpcId "v1"}}
             {:DBInstanceIdentifier "production-replica" :ReadReplicaSourceDBInstanceIdentifier "production"
              :PreferredMaintenanceWindow "tue:01:00-tue:02:00" :DBSubnetGroup {:VpcId "v1"}}
             {:DBInstanceIdentifier "staging" :ReadReplicaDBInstanceIdentifiers ["staging-replica"]
              :PreferredMaintenanceWindow "tue:02:00-tue:03:00" :MonitoringInterval 60}
             {:DBInstanceIdentifier "staging-replica" :ReadReplicaSourceDBInstanceIdentifier "staging"
              :PreferredMaintenanceWindow "tue:03:00-tue:04:00"}]
            "production" snapshot-id "staging")))

    (is (= [(op/create-replica "production" "temp-staging" {:Tags tags})
            (op/promote "temp-staging")
            (op/enable-backups "temp-staging" {})
            (op/create-replica "temp-staging" "temp-staging-replica" {:Tags tags})
            (op/modify "temp-staging-replica" {})
            (op/rename "staging-replica" "old-staging-replica")
            (op/rename "staging" "old-staging")
            (op/rename "temp-staging-replica" "staging-replica")
            (op/rename "temp-staging" "staging")
            (op/shell-command "./restart.sh")
            (op/delete "old-staging-replica")
            (op/delete "old-staging")]
           (plan/replace-tree instances "production" nil "staging"
                              :restart "./restart.sh"
                              :tags {"staging" tags
                                     "staging-replica" tags})))))

(deftest attempt
  (let [instances [{:DBInstanceIdentifier "a"}
                   {:DBInstanceIdentifier "b"}]]
    (is (= [:ok (op/create {:DBInstanceIdentifier "c"})]
           (plan/attempt instances (op/create {:DBInstanceIdentifier "c"}))))
    (is (= [:skip (plan/duplicate-instance "a")]
           (plan/attempt instances (op/create {:DBInstanceIdentifier "a"}))))
    (is (= [:skip (plan/duplicate-instance "b")]
           (plan/attempt instances (op/create-replica "a" "b"))))
    (is (= [:skip (plan/duplicate-instance "b")]
           (plan/attempt instances (op/restore-snapshot "c" "a" "b"))))

    (is (= [:skip (plan/promoted-instance "a")]
           (plan/attempt instances (op/promote "a"))))
    (is (= [:ok (op/promote "a")]
           (plan/attempt [{:DBInstanceIdentifier "a" :ReadReplicaSourceDBInstanceIdentifier "b"}]
                         (op/promote "a")))))
  (testing "modify"
    (is (= [:ok (op/enable-backups "x")]
           (plan/attempt [{:DBInstanceIdentifier "x"}]
                         (op/enable-backups "x"))))
    (is (= [:skip (plan/no-changes "x")]
           (plan/attempt [{:DBInstanceIdentifier "x" :BackupRetentionPeriod 1}]
                         (op/enable-backups "x"))))
    (is (= [:ok (op/enable-backups "x")]
           (plan/attempt [{:DBInstanceIdentifier "x" :BackupRetentionPeriod 0}]
                         (op/enable-backups "x"))))
    (is (= [:ok (op/enable-backups "x")]
           (plan/attempt [{:DBInstanceIdentifier "x" :BackupRetentionPeriod 2}]
                         (op/enable-backups "x")))))
  (testing "rename"
    (is (= [:ok (op/rename "x" "y")]
           (plan/attempt [{:DBInstanceIdentifier "x"}]
                         (op/rename "x" "y"))))
    (is (= [:skip (plan/no-changes "x")]
           (plan/attempt [{:DBInstanceIdentifier "x"}]
                         (op/rename "x" "x"))))
    ;; should rename of missing instance or duplicate instance error?
    )

  (testing "add-tags"
    (let [tags [(op/kv :k :v)]]
      (is (= [:skip (op/add-tags "x" tags)]
             (plan/attempt [] (op/add-tags "x" tags)))
          "skips if instance is missing")
      (is (= [:ok (op/add-tags "y" tags)]
             (plan/attempt [{:DBInstanceIdentifier "x" :DBInstanceArn "y"}]
                           (op/add-tags "x" tags)))
          "translates from instance id to arn if available"))
    ))
