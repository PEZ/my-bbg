(ns loc
  (:require [babashka.process :as p]))

(defn count! []
  (p/shell
   "cloc"
   "--vcs=git"
   "--exclude-lang=Markdown"
   "--exclude-dir=build,playwright-report,test-results"
   "--not-match-f=\\.mjs$"
   "."))
