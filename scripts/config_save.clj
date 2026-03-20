(ns config-save
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(def config-dirs
  ["/Users/pez"
   "/Users/pez/.config"
   "/Users/pez/.copilot"
   "/Users/pez/Library/Application Support/Code/User"
   "/Users/pez/Library/Application Support/Code - Insiders/User"])

;; ============================================================
;; Pure helpers
;; ============================================================

(defn- git-out [dir & args]
  (str/trim (:out (apply p/shell {:dir dir :out :string} "git" args))))

(defn- repo-status [dir]
  {:dir    dir
   :branch (git-out dir "rev-parse" "--abbrev-ref" "HEAD")
   :dirty? (not (str/blank? (git-out dir "status" "--porcelain")))})

(defn- commit-msg []
  (str "chore: save config " (-> (java.time.Instant/now) str (subs 0 16))))

;; ============================================================
;; Side-effecting edge
;; ============================================================

(defn- git! [dir & args]
  (apply p/shell {:dir dir} "git" args))

(defn save! []
  (let [repos (mapv repo-status config-dirs)]
    (doseq [{:keys [dir branch dirty?]} repos]
      (println (str "\n==> " dir))
      (if dirty?
        (let [msg (commit-msg)]
          (git! dir "add" "--all")
          (git! dir "commit" "-m" msg)
          (git! dir "push" "origin" branch))
        (println "Nothing to commit.")))))
