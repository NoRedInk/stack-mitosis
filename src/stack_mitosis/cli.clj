(ns stack-mitosis.cli
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [stack-mitosis.interpreter :as interpreter]
            [stack-mitosis.lookup :as lookup]
            [stack-mitosis.planner :as plan]
            [stack-mitosis.policy :as policy]
            [stack-mitosis.request :as r]
            [stack-mitosis.sudo :as sudo]))

;; TODO: add max-timeout for actions
;; TODO: show attempt info like skipped steps in flight plan?
;; TODO: add operation to copy-tree instead of replace
;; TODO: add operation to refresh replicas for a tree
(def cli-options
  [["-s" "--source SRC" "Root identifier of database tree to copy from"]
   ["-t" "--target DST" "Root identifier of database tree to copy over"]
   [nil "--restart CMD" "Blocking script to restart application."]
   ["-c" "--credentials FILENAME" "Credentials file in edn for iam assume-role"]
   ["-p" "--plan" "Display expected flightplan for operation."]
   ["-i" "--iam-policy" "Generate IAM policy for planned actions."]
   [nil "--restore-snapshot" "Always clone using snapshot restore."]
   [nil "--use-continuous-backup" "Use the first available continuous backup"]
   ["-h" "--help"]])

(defn parse-args [args]
  (let [{:keys [options errors summary]}
        (cli/parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-msg summary :ok true}
      errors
      {:exit-msg [summary errors] :ok false}
      :else
      options)))

(defn flight-plan
  [plan]
  (->> plan
       (map (fn [[step-plan reason]]
              (case step-plan
                :ok (r/explain reason)
                :skip (format "Skipping: %s" reason))))
       (concat ["Flight plan:"])
       (str/join "\n")))

(defn process
  [{:keys [source target restart] :as options}]
  (when-let [creds (:credentials options)]
    (let [role (sudo/load-role creds)]
      (log/infof "Assuming role %s" (:role-arn role))
      (sudo/sudo-provider role)))
  (let [rds (interpreter/client)
        instances (interpreter/databases rds)]
    (when (interpreter/verify-databases-exist instances [source target])
      (let [same-vpc (lookup/same-vpc?
                      (lookup/by-id instances source)
                      (lookup/by-id instances target))
            use-restore-snapshot (or (:restore-snapshot options) (not same-vpc))
            source-snapshot (if use-restore-snapshot
                              (interpreter/latest-snapshot rds source)
                              nil)]

        (when (or (not use-restore-snapshot)
                  (interpreter/verify-snapshot-exists instances [source target]
                                                      source-snapshot))
          (let [tags (interpreter/list-tags rds instances target)
                plan (plan/replace-tree instances source source-snapshot target
                                        :restart restart :tags tags)]
            (cond (:plan options)
                  (do (println (flight-plan (interpreter/check-plan instances plan)))
                      true)
                  (:iam-policy options)
                  (do (json/pprint (policy/from-plan instances plan))
                      true)
                  :else
                  (let [last-action (interpreter/evaluate-plan rds plan)]
                    (not (contains? last-action :ErrorResponse))))))))))

(defn -main [& args]
  (let [{:keys [ok exit-msg] :as options} (parse-args args)]
    (when exit-msg
      (println exit-msg)
      (System/exit (if ok 0 1)))
    (System/exit (if (process options) 0 1))))

(comment
  (process (parse-args ["--source" "mitosis-prod" "--target" "mitosis-demo"
                        "--plan" "--restart" "'./service-restart.sh'"]))
  (process (parse-args ["--source" "mitosis-prod" "--target" "mitosis-demo"
                        "--plan" "--credentials" "resources/role.edn"]))
  (process (parse-args ["--source" "mitosis-prod" "--target" "mitosis-demo"
                        "--iam-policy"]))
  (process (parse-args ["--source" "mitosis-prod" "--target" "mitosis-demo"])))
