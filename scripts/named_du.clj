(ns named-du
  "List disk usage for directories matching a name or path under a root.
   Gather-only: no deletes."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def ^:private unit-mult
  {"K" 1024.0
   "M" (* 1024.0 1024.0)
   "G" (* 1024.0 1024.0 1024.0)
   "T" (* 1024.0 1024.0 1024.0 1024.0)
   "P" (* 1024.0 1024.0 1024.0 1024.0 1024.0)})

(defn parse-du-size
  "Parse du -h sizes like 4.0K, 195G, 0B into approximate bytes (double).
   Returns 0.0 when unparseable."
  [s]
  (when (and s (not (str/blank? s)))
    (let [s (str/trim s)]
      (cond
        (re-matches #"[0-9.]+" s) (Double/parseDouble s)
        (re-matches #"[0-9.]+B" s) (Double/parseDouble (subs s 0 (dec (count s))))
        :else (when-let [[_ n unit] (re-matches #"([0-9.]+)([KMGTP])B?i?" s)]
                (* (Double/parseDouble n) (unit-mult unit)))))))

(defn format-bytes
  "Format approximate bytes as a du-style human size string."
  [n]
  (let [n (or n 0.0)]
    (cond
      (>= n (* 1024.0 1024.0 1024.0 1024.0))
      (str (format "%.1f" (/ n (* 1024.0 1024.0 1024.0 1024.0))) "T")

      (>= n (* 1024.0 1024.0 1024.0))
      (str (format "%.1f" (/ n (* 1024.0 1024.0 1024.0))) "G")

      (>= n (* 1024.0 1024.0))
      (str (format "%.1f" (/ n (* 1024.0 1024.0))) "M")

      (>= n 1024.0)
      (str (format "%.1f" (/ n 1024.0)) "K")

      :else (str (format "%.0f" n) "B"))))

(defn format-row
  [{:keys [size path]}]
  (str size \tab path))

(defn normalize-pattern
  "Normalize a directory name or relative path pattern for globbing."
  [pattern]
  (-> pattern str str/trim
      (str/replace #"^\./" "")
      (str/replace #"^/" "")
      (str/replace #"^\*/" "")))

(defn- under?
  "True when child is under parent path (not equal)."
  [parent child]
  (str/starts-with? (str child)
                    (str parent (System/getProperty "file.separator"))))

(defn- prune-nested
  "Keep outermost paths only (find -prune semantics for nested matches)."
  [paths]
  (reduce (fn [kept p]
            (if (some #(under? % p) kept)
              kept
              (conj kept p)))
          []
          (sort (map str paths))))

(defn find-named-dirs
  "Find directories under root matching pattern via fs/glob.
   Name or path patterns both use **/pattern. :hidden true required for dot dirs.
   Also checks root/pattern directly — **/pattern misses a top-level match.
   Nested matches under another match are pruned (like find -prune)."
  [root pattern]
  (let [pattern (normalize-pattern pattern)
        root (str root)
        opts {:hidden true}
        direct (fs/path root pattern)
        hits (cond-> (vec (fs/glob root (str "**/" pattern) opts))
               (fs/directory? direct) (conj direct))]
    (->> hits
         (filter fs/directory?)
         prune-nested
         vec)))

(defn du-sh
  "Run du -sh on path. Returns {:path :size :bytes :exit} or nil."
  [path]
  (when (fs/exists? path)
    (let [{:keys [out exit]} (p/sh {:continue true} "du" "-sh" (str path))
          line (->> (or out "")
                    str/split-lines
                    (remove str/blank?)
                    first)
          [raw-size du-path] (when line (str/split line #"\t" 2))
          size (some-> raw-size str/trim not-empty)]
      (when size
        {:path (or du-path (str path))
         :size size
         :bytes (or (parse-du-size size) 0.0)
         :exit exit}))))

(defn gather-pattern
  "Gather disk usage for one pattern under root.
   Returns {:pattern :count :total :total-bytes :entries}."
  [root pattern]
  (let [entries (->> (find-named-dirs root pattern)
                     (map du-sh)
                     (remove nil?)
                     (sort-by :bytes >)
                     vec)
        total-bytes (reduce + 0.0 (map :bytes entries))]
    {:pattern pattern
     :count (count entries)
     :total (format-bytes total-bytes)
     :total-bytes total-bytes
     :entries entries}))

(defn gather
  "Gather disk usage for multiple patterns under root.
   Returns {:root :groups}."
  [root patterns]
  {:root (str root)
   :groups (mapv #(gather-pattern root %) patterns)})

(def cli-spec
  {:coerce {:limit :long
            :edn :boolean}
   :alias {:r :root
           :l :limit
           :e :edn}
   :spec {:root {:alias :r
                 :desc "Root directory to search (default ~/Projects)"
                 :require false}
          :limit {:alias :l
                  :desc "Max rows to print per pattern (totals still full)"
                  :require false}
          :edn {:alias :e
                :desc "Print results as EDN"
                :require false}}})

(defn- default-root []
  (str (fs/path (System/getProperty "user.home") "Projects")))

(defn- help-text []
  (str "Usage: bb named-du <name-or-path...> [--root DIR] [--limit N] [--edn]\n\n"
       "Options:\n"
       (cli/format-opts cli-spec)))

(defn- print-usage! []
  (binding [*out* *err*]
    (println (help-text))))

(defn- maybe-limit-entries
  "If limit is set, truncate :entries in each group (totals unchanged)."
  [data limit]
  (if-not limit
    data
    (update data :groups
            (fn [groups]
              (mapv #(update % :entries (fn [es] (vec (take limit es))))
                    groups)))))

(defn- print-text-report!
  [{:keys [groups]} limit]
  (doseq [{:keys [pattern count total entries]} groups]
    (println (str "=== " pattern " (" count " dirs, " total ") ==="))
    (doseq [row (cond->> entries limit (take limit))]
      (println (format-row row)))))

(defn- print-edn-report!
  [data limit]
  (println (pr-str (maybe-limit-entries data limit))))

(defn- run-report!
  [{:keys [root limit edn]} patterns]
  (let [data (gather root patterns)]
    (if edn
      (print-edn-report! data limit)
      (print-text-report! data limit))))

(defn report!
  "Parse CLI args, gather usage for each pattern, and print a report."
  [args]
  (try
    (let [{:keys [opts args]} (cli/parse-args args cli-spec)
          root (or (:root opts) (default-root))
          patterns (mapv str args)]
      (if (empty? patterns)
        (print-usage!)
        (run-report! (assoc opts :root root) patterns)))
    (catch Exception e
      (if (= :org.babashka/cli (:type (ex-data e)))
        (print-usage!)
        (throw e)))))
