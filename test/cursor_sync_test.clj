(ns cursor-sync-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [cursor-sync :as sut]))

(defn- with-temp-fixture [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (f tmp)
      (finally (fs/delete-tree tmp)))))

(deftest manifest-format-test
  (is (= (sorted-map "a.first" "0.1.0" "m.four" "1.2.3.4" "z.last" "2.0.0-beta.1+build.7")
         (sut/parse-manifest
          "# exported extensions\n\nZ.Last@2.0.0-beta.1+build.7\r\na.first@0.1.0\nm.four@1.2.3.4\n")))
  (is (= "a.first@0.1.0\nz.last@2.0.0\n"
         (sut/render-manifest {"z.last" "2.0.0" "a.first" "0.1.0"})))
  (is (= ["a.first" "z.last"]
         (vec (keys (sut/parse-manifest "z.last@2.0.0\na.first@0.1.0\n"))))))

(deftest invalid-manifest-test
  (doseq [manifest ["" "\n# comment only\n"
                    "publisher.name"
                    "--install-extension@1.0.0"
                    "../name@1.0.0"
                    "publisher.name@latest"
                    "publisher.name@1.2"
                    "publisher.name@1.2.3 --force"
                    "publisher.name@1.2.3\npublisher.name@1.2.3"
                    "Publisher.Name@1.2.3\npublisher.name@2.0.0"]]
    (testing (pr-str manifest)
      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/parse-manifest manifest))))))

(deftest import-plan-test
  (is (= [{:id "a.missing" :version "1.0.0" :installed-version nil}
          {:id "b.downgrade" :version "1.0.0" :installed-version "2.0.0"}
          {:id "c.upgrade" :version "2.0.0" :installed-version "1.0.0"}]
         (sut/import-plan {"z.same" "1.0.0" "c.upgrade" "2.0.0"
                           "b.downgrade" "1.0.0" "a.missing" "1.0.0"}
                          {"z.same" "1.0.0" "b.downgrade" "2.0.0"
                           "c.upgrade" "1.0.0" "extra.local" "3.0.0"})))
  (is (= [] (sut/import-plan {"a.same" "1.0.0"}
                            {"a.same" "1.0.0" "extra.local" "3.0.0"}))))

(deftest invalid-cli-test
  (let [calls (atom [])]
    (with-redefs [sut/run-cursor! (fn [& args] (swap! calls conj args))]
      (doseq [argv [[] ["--export" "--import"]
                    ["--import" "--unknown"] ["--import" "stray"]
                    ["--import" "--file"] ["--import" "--cursor"]]]
        (testing (pr-str argv)
          (is (thrown? clojure.lang.ExceptionInfo (sut/exec! argv)))))
      (is (empty? @calls))
      (is (string? (with-out-str (sut/exec! ["--help"]))))
      (is (empty? @calls)))))

(deftest failed-export-preserves-file-test
  (with-temp-fixture
    (fn [tmp]
      (let [file (fs/file tmp "extensions.txt")
            original "old.extension@1.0.0\n"]
        (spit file original)
        (with-redefs [sut/run-cursor! (fn [_ _]
                                       {:exit 1 :out "" :err "listing failed"})]
          (is (thrown? clojure.lang.ExceptionInfo
                       (sut/exec! ["--export" "--file" (str file)]))))
        (is (= original (slurp file)))))))

(deftest successful-export-test
  (with-temp-fixture
    (fn [tmp]
      (let [file (fs/file tmp "extensions.txt")
            calls (atom [])]
        (with-redefs [sut/run-cursor! (fn [executable args]
                                       (swap! calls conj [executable args])
                                       {:exit 0 :out "z.last@2.0.0\na.first@1.0.0\n" :err ""})]
          (with-out-str
            (sut/exec! ["--export" "--no-dry-run" "--file" (str file) "--cursor" "/custom/cursor"])))
        (is (= "a.first@1.0.0\nz.last@2.0.0\n" (slurp file)))
        (is (= [["/custom/cursor" ["--list-extensions" "--show-versions"]]] @calls))))))

(deftest dry-run-preserves-manifest-and-extensions-test
  (with-temp-fixture
    (fn [tmp]
      (let [file (fs/file tmp "extensions.txt")
            original "# keep this comment\na.missing@1.0.0\n"
            calls (atom [])]
        (spit file original)
        (with-redefs [sut/run-cursor! (fn [_ args]
                                       (swap! calls conj args)
                                       {:exit 0 :out "extra.local@3.0.0\n" :err ""})]
          (with-out-str (sut/exec! ["--import" "--file" (str file)])))
        (is (= original (slurp file)))
        (is (= [["--list-extensions" "--show-versions"]] @calls))))))

(deftest import-continues-after-failed-install-test
  (with-temp-fixture
    (fn [tmp]
      (let [file (fs/file tmp "extensions.txt")
            calls (atom [])]
        (spit file "a.failed@1.0.0\nb.later@1.0.0\n")
        (with-redefs [sut/run-cursor! (fn [_ args]
                                       (swap! calls conj args)
                                       (if (= "--install-extension" (first args))
                                         {:exit 1 :out "" :err "unavailable"}
                                         {:exit 0 :out "extra.local@1.0.0\n" :err ""}))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (with-out-str (sut/exec! ["--import" "--no-dry-run" "--file" (str file)])))))
        (is (= ["a.failed@1.0.0" "b.later@1.0.0"]
               (mapv second (filter #(= "--install-extension" (first %)) @calls))))))))

(deftest import-detects-silent-version-substitution-test
  (with-temp-fixture
    (fn [tmp]
      (let [file (fs/file tmp "extensions.txt")
            listings (atom 0)]
        (spit file "publisher.name@1.0.0\n")
        (with-redefs [sut/run-cursor! (fn [_ args]
                                       (if (= "--list-extensions" (first args))
                                         {:exit 0
                                          :out (if (= 1 (swap! listings inc))
                                                 "publisher.name@3.0.0\n"
                                                 "publisher.name@2.0.0\n")
                                          :err ""}
                                         {:exit 0 :out "Installed successfully" :err ""}))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (with-out-str (sut/exec! ["--import" "--no-dry-run" "--file" (str file)])))))
        (is (= 2 @listings))))))

(deftest invalid-export-preserves-file-test
  (with-temp-fixture
    (fn [tmp]
      (let [file (fs/file tmp "extensions.txt")
            original "old.extension@1.0.0\n"]
        (spit file original)
        (doseq [output ["" "unexpected CLI warning\npublisher.name@1.0.0\n"]]
          (with-redefs [sut/run-cursor! (fn [_ _] {:exit 0 :out output :err ""})]
            (is (thrown? clojure.lang.ExceptionInfo
                         (with-out-str (sut/exec! ["--export" "--file" (str file)])))))
          (is (= original (slurp file))))))))

(deftest default-export-is-dry-run-test
  (with-temp-fixture
    (fn [tmp]
      (let [file (fs/file tmp "extensions.txt")
            original "old.extension@1.0.0\n"]
        (spit file original)
        (with-redefs [sut/run-cursor! (fn [_ _]
                                       {:exit 0 :out "a.first@1.0.0\n" :err ""})]
          (is (re-find #"Would export"
                       (with-out-str
                         (sut/exec! ["--export" "--file" (str file)])))))
        (is (= original (slurp file)))))))

(deftest missing-manifest-test
  (let [calls (atom [])]
    (with-redefs [sut/run-cursor! (fn [& args] (swap! calls conj args) {:exit 0 :out "" :err ""})]
      (try
        (sut/exec! ["--import" "--file" "/no/such/cursor-sync-manifest.txt" "--cursor" "/fake/cursor"])
        (is false "expected missing manifest to fail")
        (catch clojure.lang.ExceptionInfo e
          (is (= 1 (:babashka/exit (ex-data e))))
          (is (re-find #"Manifest file not found" (ex-message e))))))
    (is (empty? @calls))))

(deftest cursor-path-expands-home-test
  (with-temp-fixture
    (fn [tmp]
      (let [file (fs/file tmp "extensions.txt")
            calls (atom [])]
        (with-redefs [sut/run-cursor!
                      (fn [executable _args]
                        (swap! calls conj executable)
                        {:exit 0 :out "a.first@1.0.0\n" :err ""})]
          (with-out-str
            (sut/exec! ["--export" "--file" (str file) "--cursor" "~/custom/cursor"])))
        (is (= [(str (fs/expand-home "~/custom/cursor"))] @calls))))))

(deftest successful-import-installs-and-downgrades-test
  (with-temp-fixture
    (fn [tmp]
      (let [dir (fs/file tmp "dir with spaces")
            file (fs/file dir "extensions.txt")
            calls (atom [])
            listings (atom 0)
            result (atom nil)]
        (fs/create-dirs dir)
        (spit file "a.missing@1.0.0\nb.downgrade@1.0.0\n")
        (with-redefs [sut/run-cursor!
                      (fn [executable args]
                        (swap! calls conj [executable args])
                        (if (= "--list-extensions" (first args))
                          {:exit 0
                           :out (if (= 1 (swap! listings inc))
                                  "b.downgrade@2.0.0\nextra.local@9.0.0\n"
                                  "a.missing@1.0.0\nb.downgrade@1.0.0\nextra.local@9.0.0\n")
                           :err ""}
                          {:exit 0 :out "ok" :err ""}))]
          (with-out-str
            (reset! result
                    (sut/exec! ["--import" "--no-dry-run" "--file" (str file)
                                "--cursor" "/fake/cursor"]))))
        (is (= {:installed 2 :verified 2} @result))
        (is (= [["/fake/cursor" ["--list-extensions" "--show-versions"]]
                ["/fake/cursor" ["--install-extension" "a.missing@1.0.0" "--force"]]
                ["/fake/cursor" ["--install-extension" "b.downgrade@1.0.0" "--force"]]
                ["/fake/cursor" ["--list-extensions" "--show-versions"]]]
               @calls))
        (is (= "a.missing@1.0.0\nb.downgrade@1.0.0\n" (slurp file)))))))
