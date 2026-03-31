(ns loc
  (:require [babashka.process :as p]))

(defn count!
  ([] (count! nil))
  ([paths]
   (apply p/shell
          "cloc"
          "--vcs=git"
          "--exclude-lang=Markdown"
          "--exclude-dir=build,playwright-report,test-results"
          "--not-match-f=\\.mjs$"
          (if (seq paths) paths ["."]))))
