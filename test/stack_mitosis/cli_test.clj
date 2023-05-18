(ns stack-mitosis.cli-test
  (:require [stack-mitosis.cli :as sut]
            [clojure.test :as t]))

(t/deftest parsing
  (t/is (= {:source "production"
            :target "staging"
            :restart "./restart.sh"}
           (sut/parse-args ["--source" "production" "--target" "staging"
                            "--restart" "./restart.sh" "production"])))
  (t/is (= {:source "production"
            :target "staging"
            :use-continuous-backup true
            :restart "./restart.sh"}
           (sut/parse-args ["--source" "production" "--target" "staging"
                            "--use-continuous-backup"
                            "--restart" "./restart.sh" "production"]))))
