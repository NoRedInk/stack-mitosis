(ns stack-mitosis.lookup-test
  (:require [stack-mitosis.lookup :as lookup]
            [clojure.test :refer :all]
            [stack-mitosis.rds-operations :as op]))

(deftest by-id
  (let [instance {:DBInstanceIdentifier :foo}]
    (is (= instance (lookup/by-id [instance] :foo)))
    (is (= nil (lookup/by-id [instance] :bar)))))

(deftest position
  (let [instances [{:DBInstanceIdentifier "a"}
                   {:DBInstanceIdentifier "b"}
                   {:DBInstanceIdentifier "c"}]]
    (is (= 0 (lookup/position instances "a")))
    (is (= 1 (lookup/position instances "b")))
    (is (= nil (lookup/position instances "missing")))))

(deftest replica-attrs
  (let [instance {:DBInstanceIdentifier "a"
                  :Iops 100
                  :VpcSecurityGroups
                  [{:VpcSecurityGroupId "sg-abcd"
                    :Status "active"}]
                  :DBParameterGroups
                  [{:DBParameterGroupName "default.postgres9.6"
                    :ParameterApplyStatus "in-sync"}]
                  :OptionGroupMemberships
                  [{:OptionGroupName "default:postgres-9-6"
                    :Status "in-sync"}]
                  :PerformanceInsightsEnabled false
                  :IAMDatabaseAuthenticationEnabled false
                  :StorageType "io1"
                  :DBInstanceArn "abcdef"
                  :Endpoint {:Port 9999}
                  :PreferredBackupWindow "06:35-07:05"
                  :PreferredMaintenanceWindow "tue:06:05-tue:06:35"
                  :DBSubnetGroup {:DBSubnetGroupName "subnet-group"}
                  }
        tags [(op/kv "k" "v")]]
    (is (= {:Port 9999
            :StorageType "io1"
            :Iops 100
            :VpcSecurityGroupIds ["sg-abcd"]
            :OptionGroupName "default:postgres-9-6"
            :EnablePerformanceInsights false
            :EnableIAMDatabaseAuthentication false}
           (lookup/clone-replica-attributes instance [])))
    (is (= {:Iops 100
            :Tags tags}
           (lookup/clone-replica-attributes {:Iops 100} tags)))
    (is (= {:PreferredBackupWindow "06:35-07:05"
            :PreferredMaintenanceWindow "tue:06:05-tue:06:35"
            :DBParameterGroupName "default.postgres9.6"
            ;; :DBSubnetGroupName "subnet-group"
            }
           (lookup/post-create-replica-attributes instance)))))

(deftest same-vpc?
  (is (= true (lookup/same-vpc? {:DBSubnetGroup {:VpcId "a"}} {:DBSubnetGroup {:VpcId "a"}})))
  (is (= false (lookup/same-vpc? {:DBSubnetGroup {:VpcId "a"}} {:DBSubnetGroup {:VpcId "b"}}))))