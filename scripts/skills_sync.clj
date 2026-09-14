(ns skills-sync
  "Merge skill dirs from ~/.agents/skills and ~/.cursor/skills into
   ~/.claude/skills as individual symlinks, so Claude Code discovers
   skills from both sources. Name clashes: the ~/.agents/skills entry
   is linked as `agents-<name>`."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private home (System/getProperty "user.home"))

(def ^:private agent-skills-dir (str (fs/path home ".agents" "skills")))
(def ^:private cursor-skills-dir (str (fs/path home ".cursor" "skills")))
(def ^:private dest-dir (str (fs/path home ".claude" "skills")))

(def cli-spec
  {:coerce {:dry-run :boolean}
   :alias {:n :dry-run}
   :spec {:dry-run {:alias :n
                    :desc "Print the plan without touching the filesystem"}}})

;; ============================================================
;; Pure helpers
;; ============================================================

(defn- skill-entries
  "Top-level skill directories under `dir`, skipping dotfiles."
  [dir]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter fs/directory?)
         (remove #(str/starts-with? (fs/file-name %) "."))
         (sort-by str))
    []))

(defn- link-plan
  "Seq of {:link-name :target}. Cursor-sourced skills keep their name.
   Agent-sourced skills keep their name unless it clashes with a
   cursor skill, in which case it's prefixed with `agents-`."
  [cursor-entries agent-entries]
  (let [cursor-names (into #{} (map fs/file-name) cursor-entries)
        cursor-links (for [entry cursor-entries]
                       {:link-name (fs/file-name entry) :target entry})
        agent-links (for [entry agent-entries]
                      (let [name (fs/file-name entry)]
                        {:link-name (if (contains? cursor-names name)
                                      (str "agents-" name)
                                      name)
                         :target entry}))]
    (concat cursor-links agent-links)))

;; ============================================================
;; Side-effecting edge
;; ============================================================

(defn- ensure-real-dir!
  "Replaces `dir` with a real directory if it's currently a symlink
   (e.g. ~/.claude/skills starting out as a symlink to ~/.cursor/skills)."
  [dir dry-run?]
  (cond
    (fs/sym-link? dir)
    (do (println (str "replace symlink " dir " -> real dir"))
        (when-not dry-run?
          (fs/delete dir)
          (fs/create-dirs dir)))

    (not (fs/exists? dir))
    (do (println (str "create dir " dir))
        (when-not dry-run? (fs/create-dirs dir)))

    :else nil))

(defn- clear-managed-links!
  "Removes existing symlinks directly under `dir` (never touches real
   files/dirs a user may have placed there), so removed/renamed skills
   don't leave stale links behind."
  [dir dry-run?]
  (when (fs/directory? dir)
    (doseq [entry (->> (fs/list-dir dir) (filter fs/sym-link?))]
      (println (str "remove stale link " entry))
      (when-not dry-run? (fs/delete entry)))))

(defn sync!
  [{:keys [dry-run] :as _opts}]
  (let [dry-run? (boolean dry-run)]
    (ensure-real-dir! dest-dir dry-run?)
    (clear-managed-links! dest-dir dry-run?)
    (doseq [{:keys [link-name target]} (link-plan (skill-entries cursor-skills-dir)
                                                   (skill-entries agent-skills-dir))]
      (let [link (fs/path dest-dir link-name)
            relative-target (fs/relativize dest-dir target)]
        (println (str link " -> " relative-target))
        (when-not dry-run? (fs/create-sym-link link relative-target))))
    (println (str "done" (when dry-run? " (dry-run)")))))

(defn exec!
  [args]
  (sync! (cli/parse-opts args cli-spec)))
