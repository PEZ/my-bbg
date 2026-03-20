(ns loc
  (:require [babashka.process :as p]))

(defn hello [& args]
    (println "Hello args" (pr-str args)))

(defn count! [{:keys [cwd]}]
  (p/shell
   {:dir cwd}
   "cloc"
   "--vcs=git"
   "--exclude-lang=Markdown"
   "--exclude-dir=build,playwright-report,test-results"
   "--not-match-f=\\.mjs$"
   "."))
