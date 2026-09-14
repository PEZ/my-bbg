(ns idea-inbox-backup
  "Apply Grok Bot idea-inbox git bundle drop onto ~/Projects/idea-inbox."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def cli-spec
  {:coerce {:dry-run :boolean
            :bundle :string
            :dest :string}
   :alias {:n :dry-run
           :b :bundle
           :d :dest}
   :spec {:dry-run {:alias :n :desc "Print plan only"}
          :bundle {:alias :b :desc "Path to idea-inbox.bundle drop"}
          :dest {:alias :d :desc "Local git clone path"}}})

(def ^:private home (System/getProperty "user.home"))
(def ^:private default-bundle (str (fs/path home "tmp" "idea-inbox.bundle")))
(def ^:private default-dest (str (fs/path home "Projects" "idea-inbox")))

(defn- sh!
  [opts & args]
  (let [result (apply p/shell (merge {:continue true} opts) args)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Command failed (" (:exit result) "): "
                           (str/join " " args)
                           "\n"
                           (or (:err result) (:out result) ""))
                      {:args args :result result})))
    result))

(defn- ensure-clone!
  [dest bundle dry-run?]
  (if (fs/exists? (fs/path dest ".git"))
    (println (str "using existing clone " dest))
    (do
      (println (str "cloning from bundle into " dest))
      (when-not dry-run?
        (fs/create-dirs (fs/parent dest))
        (sh! {} "git" "clone" (str bundle) (str dest))))))

(defn- clear-stale-inbox-ref!
  [dest]
  (let [ref-path (fs/path dest ".git" "refs" "remotes" "inbox" "main")
        lock-path (fs/path dest ".git" "refs" "remotes" "inbox" "main.lock")]
    (when (fs/exists? lock-path) (fs/delete lock-path))
    ;; Broken/corrupt ref from a prior failed fetch
    (when (fs/exists? ref-path) (fs/delete ref-path))
    (let [packed (fs/path dest ".git" "packed-refs")]
      (when (fs/exists? packed)
        (sh! {:dir (str dest) :continue true}
             "git" "update-ref" "-d" "refs/remotes/inbox/main")))))

(defn- fetch-bundle!
  [dest bundle dry-run?]
  (println (str "fetch bundle -> " dest))
  (when-not dry-run?
    (clear-stale-inbox-ref! dest)
    (sh! {:dir (str dest)}
         "git" "fetch" "--force" (str bundle) "+main:refs/remotes/inbox/main")
    (let [status (-> (p/shell {:dir (str dest) :out :string :continue true}
                              "git" "status" "--porcelain")
                     :out str/trim)]
      (when-not (str/blank? status)
        (println "WARN: local modifications in idea-inbox; leaving working tree untouched")
        (println status)
        (println "Fetched to refs/remotes/inbox/main — merge/rebase manually."))
      (when (str/blank? status)
        (sh! {:dir (str dest)} "git" "checkout" "-B" "main" "refs/remotes/inbox/main")
        (println "main updated from inbox bundle")))))

(defn backup!
  [{:keys [dry-run bundle dest]}]
  (let [bundle (or (not-empty bundle) default-bundle)
        dest (or (not-empty dest) default-dest)
        dry-run? (boolean dry-run)]
    (println (str "idea-inbox-backup"
                  (when dry-run? " (dry-run)")
                  "\n  bundle: " bundle
                  "\n  dest:   " dest))
    (when-not (fs/exists? bundle)
      (throw (ex-info (str "Missing bundle drop: " bundle
                           " (Grok Bot should CopyFromBox idea-inbox.bundle here first)")
                      {:bundle bundle})))
    (ensure-clone! dest bundle dry-run?)
    (when (fs/exists? (fs/path dest ".git"))
      (fetch-bundle! dest bundle dry-run?))
    (when-not dry-run?
      (println "\nRecent:")
      (sh! {:dir (str dest)} "git" "log" "-3" "--oneline")
      (println "\nPush when ready: cd ~/Projects/idea-inbox && git push origin main"))
    (println "done")))

(defn exec! [args]
  (backup! (cli/parse-opts args cli-spec)))
