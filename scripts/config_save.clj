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
  (str/trim (:out (apply p/shell {:dir dir :out :string :err :string} "git" args))))

(defn- unpushed-count [dir]
  (try
    (parse-long (git-out dir "rev-list" "@{u}..HEAD" "--count"))
    (catch Exception _ nil)))

(defn- repo-status [dir]
  {:dir      dir
   :dirty?   (not (str/blank? (git-out dir "status" "--porcelain")))
   :unpushed (unpushed-count dir)})

(defn- commit-msg []
  (str "chore: save config " (-> (java.time.Instant/now) str (subs 0 16))))

(defn- status-label [{:keys [dirty? unpushed]}]
  (cond
    dirty?                          "uncommitted"
    (and unpushed (pos? unpushed))  (str unpushed " unpushed")
    (nil? unpushed)                 "no upstream"
    :else                           "clean"))

;; ============================================================
;; Side-effecting edge
;; ============================================================

(defn- git! [dir & args]
  (apply p/shell {:dir dir} "git" args))

(defn save! []
  (let [repos (mapv repo-status config-dirs)]
    (doseq [{:keys [dir dirty?]} repos]
      (println (str "\n==> " dir))
      (if dirty?
        (let [msg    (commit-msg)
              branch (git-out dir "rev-parse" "--abbrev-ref" "HEAD")]
          (git! dir "add" "--all")
          (git! dir "commit" "-m" msg)
          (git! dir "push" "origin" branch))
        (println "Nothing to commit.")))))

(defn status! []
  (let [statuses (mapv repo-status config-dirs)
        max-len  (apply max (map #(count (:dir %)) statuses))]
    (doseq [s statuses]
      (println (format (str "%-" max-len "s  %s") (:dir s) (status-label s))))))

(defn exec! [{:keys [status save]}]
  (cond
    status (status!)
    save   (do (save!) (status!))
    :else  (status!)))
