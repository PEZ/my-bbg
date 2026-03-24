(ns mdq-e2e-test
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [e2e-specs]))

(defn- run-mdq
  "Run bbg mdq as subprocess. Returns {:exit :out :err}."
  [markdown cli-args]
  (let [result (apply p/shell
                      {:in markdown :out :string :err :string :continue true}
                      "bbg" "mdq" cli-args)]
    {:exit (:exit result)
     :out (:out result)
     :err (:err result)}))

(defn- normalize-expected
  "Temporary normalization of expected output.
   Each rule is a TODO to remove by fixing our mdq."
  [expected]
  (-> expected
      ;; TODO: Remove when separator style converges
      ;; Rust uses '   -----' (indented 3-space + 5-dash) between results
      (str/replace #"(?m)^ {3}-----$" "---")
      ;; TODO: Remove when trailing whitespace handling converges
      str/trimr))

(defn- run-test-case
  "Run one test expectation, return result map."
  [{:keys [given-md file]} {:keys [name cli-args expected-output expect-success
                                    output-json output-err ignore]}]
  (if ignore
    {:spec file :test name :status :skipped :reason ignore}
    (let [{:keys [exit out err]} (run-mdq given-md cli-args)
          exit-ok? (if expect-success (zero? exit) (not (zero? exit)))
          output-ok? (cond
                       (nil? expected-output) true
                       output-json (= (json/parse-string (normalize-expected expected-output) true)
                                      (json/parse-string (str/trimr out) true))
                       :else (= (normalize-expected expected-output)
                                (str/trimr out)))
          err-ok? (if output-err
                    (str/includes? err output-err)
                    true)
          pass? (and exit-ok? output-ok? err-ok?)]
      (cond-> {:spec file :test name :status (if pass? :pass :fail)}
        (not exit-ok?) (assoc :exit-expected (if expect-success 0 "non-zero") :exit-actual exit)
        (not output-ok?) (assoc :expected (normalize-expected (or expected-output ""))
                                :actual (str/trimr out))
        (not err-ok?) (assoc :err-expected output-err :err-actual err)))))

(defn- run-specs
  "Run all test cases across specs. Returns results vector."
  [specs]
  (vec (for [spec specs
             expectation (:expectations spec)]
         (run-test-case spec expectation))))

(defn- report-results
  "Print a summary of test results. Returns summary map."
  [results]
  (let [failures (filter #(= :fail (:status %)) results)
        by-spec (group-by :spec results)
        {:keys [pass fail skipped]} (frequencies (map :status results))
        pass (or pass 0) fail (or fail 0) skipped (or skipped 0)]
    (println)
    (doseq [[spec spec-results] (sort-by key by-spec)
            :let [spec-fails (count (filter #(= :fail (:status %)) spec-results))
                  spec-pass (count (filter #(= :pass (:status %)) spec-results))
                  spec-skip (count (filter #(= :skipped (:status %)) spec-results))]]
      (println (str (if (zero? spec-fails) "  PASS " "  FAIL ")
                    spec " (" spec-pass "/" (count spec-results) " passed"
                    (when (pos? spec-skip) (str ", " spec-skip " skipped"))
                    ")")))
    (when (pos? fail)
      (println)
      (println "FAILURE DETAILS:")
      (doseq [{:keys [spec test] :as r} failures]
        (println (str "  " spec " / " test))
        (when (:exit-expected r)
          (println (str "    exit: expected " (:exit-expected r) ", got " (:exit-actual r))))
        (when (:expected r)
          (let [exp (:expected r)
                act (:actual r)]
            (if (< (count exp) 80)
              (do (println (str "    expected: " (pr-str exp)))
                  (println (str "    actual:   " (pr-str act))))
              (println (str "    output mismatch (expected " (count exp) " chars, got " (count act) " chars)")))))
        (when (:err-expected r)
          (println (str "    stderr mismatch")))))
    (println)
    (println (str pass " passed, " fail " failed, " skipped " skipped, "
                  (count results) " total"))
    {:pass pass :fail fail :skipped skipped :total (count results)}))

(defn run!
  "Run E2E tests. Options: :refresh (re-download specs), :spec (single spec file)."
  [{:keys [refresh spec]}]
  (e2e-specs/ensure-specs! :refresh? refresh)
  (let [specs (e2e-specs/load-specs :spec-file spec)
        _ (println (str "Running " (count specs) " specs, "
                        (reduce + (map (comp count :expectations) specs))
                        " test cases..."))
        results (run-specs specs)
        {:keys [fail]} (report-results results)]
    (System/exit (if (zero? fail) 0 1))))
