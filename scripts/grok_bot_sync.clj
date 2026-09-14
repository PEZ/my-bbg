(ns grok-bot-sync
  "Lean sync of Calva-related projects for Grok Bot daily reports.
   Builds working-tree overlays + git-meta packs under ~/tmp (no .git)."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def cli-spec
  {:coerce {:dry-run :boolean
            :projects :string}
   :alias {:n :dry-run
           :p :projects}
   :spec {:dry-run {:alias :n
                    :desc "Print the plan without rsync/tar/bundle"}
          :projects {:alias :p
                     :desc "Comma-separated project folder names under ~/Projects"}}})

(def ^:private home (System/getProperty "user.home"))

(def ^:private default-projects
  ["calva" "joyride" "backseat-driver" "awesome-backseat-driver"])

(def ^:private projects-root (str (fs/path home "Projects")))
(def ^:private staging-root (str (fs/path home "tmp" "grok-calva-projects-sync")))
(def ^:private meta-root (str (fs/path home "tmp" "grok-calva-git-meta")))
(def ^:private tmp-root (str (fs/path home "tmp")))

;; Anchored at transfer root. Keep .clj-kondo configs; drop caches only.
(def ^:private rsync-excludes
  ["/.git/"
   "/node_modules/"
   "/.vscode-test/"
   "/.shadow-cljs/"
   "/.cpcache/"
   "/.clj-kondo/.cache/"
   "/.lsp/.cache/"
   "/out/"
   "/dist/"
   "/.venv-docs/"
   "/site/"
   "/docs/site/"
   "/test-data/"
   "/backup/"
   "/.cache/"
   "/.e2e-logs/"
   "/clojure-lsp"
   "clojure-lsp-native-*.zip"
   "deps.clj.jar"
   "*.vsix"
   "._*"
   ".DS_Store"])

(defn- parse-projects [s]
  (if (str/blank? s)
    default-projects
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn- sh!
  [opts & args]
  (let [result (apply p/shell (merge {:continue true} opts) args)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Command failed (" (:exit result) "): "
                           (str/join " " args))
                      {:args args :result result})))
    result))

(defn- rsync-project!
  [name dry-run?]
  (let [src (str (fs/path projects-root name) "/")
        dest (str (fs/path staging-root name) "/")]
    (when-not (fs/directory? src)
      (throw (ex-info (str "Missing project: " src) {:project name})))
    (fs/create-dirs dest)
    (let [exclude-args (mapcat (fn [pat] ["--exclude" pat]) rsync-excludes)
          cmd (concat ["rsync" "-a" "--delete" "--delete-excluded"]
                      exclude-args
                      [src dest])]
      (println (str "rsync " name))
      (when-not dry-run?
        (apply sh! {:extra-env {"COPYFILE_DISABLE" "1"}} cmd)))))

(defn- git-meta-for!
  [name dry-run?]
  (let [repo (str (fs/path projects-root name))
        out-txt (str (fs/path meta-root (str name ".txt")))
        bundle (str (fs/path meta-root (str name "-local.bundle")))
        git (fn [& args]
              (-> (apply p/shell {:dir repo :out :string :err :string :continue true} "git" args)
                  :out
                  (or "")
                  str/trim))]
    (println (str "git-meta " name))
    (when-not dry-run?
      (fs/create-dirs meta-root)
      (when (fs/exists? bundle)
        (fs/delete bundle))
      (let [branch (git "rev-parse" "--abbrev-ref" "HEAD")
            head (git "rev-parse" "HEAD")
            status (git "status" "--short" "--branch")
            stash (git "stash" "list")
            log (git "log" "--oneline" "-20")
            upstream (let [r (p/shell {:dir repo :out :string :err :string :continue true}
                                      "git" "rev-parse" "--abbrev-ref" "@{upstream}")]
                       (when (zero? (:exit r))
                         (str/trim (:out r))))
            ahead-behind (when upstream
                           (git "rev-list" "--left-right" "--count" (str upstream "...HEAD")))
            diff (git "diff" "HEAD")
            untracked (->> (str/split-lines (git "ls-files" "--others" "--exclude-standard"))
                           (remove str/blank?)
                           vec)
            ;; Local commits not on upstream (if upstream exists)
            local-range (when upstream (str upstream "..HEAD"))
            has-local-commits? (when local-range
                                 (pos? (count (remove str/blank?
                                                      (str/split-lines (git "rev-list" local-range))))))]
        (when (and has-local-commits? local-range)
          (sh! {:dir repo} "git" "bundle" "create" bundle local-range))
        (spit out-txt
              (str "project: " name "\n"
                   "path: " repo "\n"
                   "branch: " branch "\n"
                   "head: " head "\n"
                   "upstream: " (or upstream "(none)") "\n"
                   "ahead_behind: " (or ahead-behind "n/a") "\n"
                   "bundle: " (if (fs/exists? bundle) (fs/file-name bundle) "(none)") "\n"
                   "\n=== status ===\n" status "\n"
                   "\n=== stash ===\n" (if (str/blank? stash) "(empty)" stash) "\n"
                   "\n=== log -20 ===\n" log "\n"
                   "\n=== untracked ===\n"
                   (if (seq untracked) (str/join "\n" untracked) "(none)")
                   "\n"
                   "\n=== diff HEAD ===\n"
                   (if (str/blank? diff) "(clean)" diff)
                   "\n"))))))

(defn- pack-project!
  [name dry-run?]
  (let [src (str (fs/path staging-root name))
        tar (str (fs/path tmp-root (str "grok-" name "-src.tar.gz")))]
    (println (str "pack " name " -> " tar))
    (when-not dry-run?
      (when (fs/exists? tar)
        (fs/delete tar))
      (sh! {:dir staging-root
            :extra-env {"COPYFILE_DISABLE" "1"}}
           "tar" "-czf" tar
           "--exclude" ".git"
           "--exclude" "._*"
           name)
      (let [bytes (fs/size tar)]
        (when (> bytes (* 100 1024 1024))
          (println (str "WARN: " tar " is " bytes " bytes (>100MiB CopyToBox limit)")))
        (println (str "  " (format "%.1f" (/ bytes 1024.0 1024.0)) " MiB"))))))

(defn- pack-meta!
  [dry-run?]
  (let [tar (str (fs/path tmp-root "grok-calva-git-meta.tar.gz"))]
    (println (str "pack git-meta -> " tar))
    (when-not dry-run?
      (when (fs/exists? tar)
        (fs/delete tar))
      (sh! {:dir tmp-root
            :extra-env {"COPYFILE_DISABLE" "1"}}
           "tar" "-czf" tar "grok-calva-git-meta")
      (println (str "  " (format "%.1f" (/ (fs/size tar) 1024.0 1024.0)) " MiB")))))

(defn- write-manifest!
  [projects dry-run?]
  (let [manifest (str (fs/path tmp-root "grok-bot-sync-manifest.txt"))
        lines (concat
               [(str "synced_at: " (java.time.Instant/now))
                (str "projects: " (str/join "," projects))
                (str "staging: " staging-root)
                (str "meta: " meta-root)
                ""]
               (for [n projects]
                 (str "pack: " (fs/path tmp-root (str "grok-" n "-src.tar.gz"))))
               [(str "pack: " (fs/path tmp-root "grok-calva-git-meta.tar.gz"))])]
    (println (str "manifest -> " manifest))
    (when-not dry-run?
      (spit manifest (str (str/join "\n" lines) "\n")))))

(defn sync!
  "Lean-sync Calva-related projects for Grok Bot."
  [{:keys [dry-run projects] :as _opts}]
  (let [projects (parse-projects projects)
        dry-run? (boolean dry-run)]
    (println (str "grok-bot-sync"
                  (when dry-run? " (dry-run)")
                  " projects=" (str/join "," projects)))
    (fs/create-dirs staging-root)
    (fs/create-dirs meta-root)
    (fs/create-dirs tmp-root)
    (doseq [n projects]
      (rsync-project! n dry-run?)
      (git-meta-for! n dry-run?)
      (pack-project! n dry-run?))
    (pack-meta! dry-run?)
    (write-manifest! projects dry-run?)
    (println "done")))

(defn exec!
  [args]
  (sync! (cli/parse-opts args cli-spec)))
