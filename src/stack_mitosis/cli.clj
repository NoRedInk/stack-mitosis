(ns stack-mitosis.cli
  (:require [clojure.tools.cli :as cli]
            [stack-mitosis.interpreter :as interpreter]
            [stack-mitosis.planner :as plan]
            [stack-mitosis.request :as r]
            [clojure.string :as str]
            [stack-mitosis.sudo :as sudo]
            [clojure.tools.logging :as log]))

;; TODO: add max-timeout for actions
;; TODO: show attempt info like skipped steps in flight plan?
;; TODO: add operation to copy-tree instead of replace
;; TODO: add operation to refresh replicas for a tree
(def cli-options
  [["-s" "--source SRC" "Root identifier of database tree to copy from"]
   ["-t" "--target DST" "Root identifier of database tree to copy over"]
   [nil "--restart CMD" "Blocking script to restart application."]
   ["-c" "--credentials FILENAME" "Credentials file in edn for iam assume-role"]
   ["-p" "--plan", "Display expected flightplan for operation."]
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
      (let [tags (interpreter/list-tags rds instances target)
            plan (plan/replace-tree instances source target
                                    :restart restart :tags tags)]
        (cond (:plan options)
              (do (println (flight-plan (interpreter/check-plan instances plan)))
                  true)
              :else
              (let [last-action (interpreter/evaluate-plan rds plan)]
                (not (contains? last-action :ErrorResponse))))))))

(defn -main [& args]
  (let [{:keys [ok exit-msg] :as options} (parse-args args)]
    (when exit-msg
      (println exit-msg)
      (System/exit (if ok 0 1)))
    (System/exit (if (process options) 0 1))
    ))

(comment
  (process (parse-args ["--source" "mitosis-prod" "--target" "mitosis-demo"
                        "--plan" "--restart" "'./service-restart.sh'"]))
  (process (parse-args ["--source" "mitosis-prod" "--target" "mitosis-demo"
                        "--plan" "--credentials" "resources/role.edn"]))
  (process (parse-args ["--source" "mitosis-prod" "--target" "mitosis-demo"])))
