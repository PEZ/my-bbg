(ns mdq-e2e-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as string]
            [e2e-specs]
            [mdq]))

(defn- run-mdq-subprocess
  "Run bbg mdq as subprocess. Returns {:exit :out :err}."
  [markdown cli-args {:keys [dir]}]
  (let [result (apply p/shell
                      (cond-> {:in markdown :out :string :err :string :continue true}
                        dir (assoc :dir dir))
                      "bbg" "mdq" cli-args)]
    {:exit (:exit result)
     :out (:out result)
     :err (:err result)}))

(defn- run-mdq-in-process
  "Run mdq in-process. Returns {:exit :out :err}.
   Adds trailing newline to output/error to match subprocess println behavior."
  [markdown cli-args {:keys [dir]}]
  (let [resolve-file (fn [path]
                       (slurp (if dir
                                (java.io.File. (str dir) path)
                                (java.io.File. path))))
        {:keys [output error exit]} (mdq/process-inputs cli-args
                                                        {:read-stdin (constantly markdown)
                                                         :resolve-file resolve-file})]
    {:exit exit
     :out (if output (str output "\n") "")
     :err (if error (str error "\n") "")}))

(defn- run-mdq
  "Run mdq, in-process by default. Pass :subprocess true for subprocess mode."
  [markdown cli-args opts]
  (if (:subprocess opts)
    (run-mdq-subprocess markdown cli-args opts)
    (run-mdq-in-process markdown cli-args opts)))



(defn- run-test-case
  "Run one test expectation, return result map."
  [{:keys [given-md given-files file]} {:keys [name cli-args expected-output expect-success
                                               output-json output-err ignore]} opts]
  (if ignore
    {:spec file :test name :status :skipped :reason ignore}
    (let [;; Create temp files if spec has file definitions
          tmp-dir (when given-files
                    (let [d (fs/create-temp-dir)]
                      (doseq [[fname content] given-files]
                        (spit (str (fs/path d (clojure.core/name fname))) content))
                      d))
          {:keys [exit out err]} (run-mdq given-md cli-args (merge opts {:dir (when tmp-dir (str tmp-dir))}))
          exit-ok? (if expect-success (zero? exit) (not (zero? exit)))
          output-ok? (cond
                       (nil? expected-output) true
                       output-json (= (json/parse-string (string/trimr expected-output) true)
                                      (json/parse-string (string/trimr out) true))
                       :else (= (string/trimr expected-output)
                                (string/trimr out)))
          err-ok? (if output-err
                    (string/includes? err output-err)
                    true)
          pass? (and exit-ok? output-ok? err-ok?)]
      (when tmp-dir (fs/delete-tree tmp-dir))
      (cond-> {:spec file :test name :status (if pass? :pass :fail)}
        (not exit-ok?) (assoc :exit-expected (if expect-success 0 "non-zero") :exit-actual exit)
        (not output-ok?) (assoc :expected (string/trimr (or expected-output ""))
                                :actual (string/trimr out))
        (not err-ok?) (assoc :err-expected output-err :err-actual err)))))

(defn- run-specs
  "Run all test cases across specs. Returns results vector."
  [specs opts]
  (vec (for [spec specs
             expectation (:expectations spec)]
         (run-test-case spec expectation opts))))

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
          (println "    stderr mismatch"))))
    (println)
    (println (str pass " passed, " fail " failed, " skipped " skipped, "
                  (count results) " total"))
    {:pass pass :fail fail :skipped skipped :total (count results)}))

(defn run-e2e!
  "Run E2E tests.
   Options: :refresh (re-download upstream cached specs), :spec (single spec file), :subprocess (use subprocess)."
  [{:keys [refresh spec] :as opts}]
  (e2e-specs/ensure-specs! :refresh? refresh)
  (let [specs (e2e-specs/load-specs :spec-file spec)
        _ (println (str "Running " (count specs) " specs, "
                        (reduce + (map (comp count :expectations) specs))
                        " test cases..."))
        results (run-specs specs (select-keys opts [:subprocess]))
        {:keys [fail]} (report-results results)]
  (System/exit (if (zero? fail) 0 1))))
