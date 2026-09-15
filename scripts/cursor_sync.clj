(ns cursor-sync
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as string]))

(def cli-spec
  {:coerce {:export :boolean :import :boolean :apply :boolean
            :help :boolean :file :string :cursor :string}})

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :babashka/exit 1))))

(defn- parse-row
  [manifest [index text]]
  (let [row (string/trim text)]
    (if (or (string/blank? row) (string/starts-with? row "#"))
      manifest
      (let [[_ id version] (re-matches #"(?i)([a-z0-9][a-z0-9-]*\.[a-z0-9][a-z0-9._-]*)@([0-9]+(?:\.[0-9]+){2,}(?:-[0-9a-z.-]+)?(?:\+[0-9a-z.-]+)?)" row)
            normalized-id (some-> id string/lower-case)]
        (when-not id
          (fail! (str "Invalid extension on line " (inc index) ": " row) {:line (inc index)}))
        (when (contains? manifest normalized-id)
          (fail! (str "Duplicate extension: " normalized-id) {:id normalized-id}))
        (assoc manifest normalized-id version)))))

(defn parse-manifest
  "Parses pinned extension IDs, rejecting malformed, duplicate, or empty manifests."
  [text]
  (let [manifest (reduce parse-row (sorted-map) (map-indexed vector (string/split-lines text)))]
    (when (empty? manifest)
      (fail! "Extension manifest is empty; refusing to continue." {}))
    manifest))

(defn render-manifest
  "Renders extension versions in stable ID order."
  [manifest]
  (apply str (map (fn [[id version]] (str id "@" version "\n")) (sort-by key manifest))))

(defn import-plan
  "Returns missing or different pinned versions, retaining extra installed extensions."
  [desired installed]
  (->> desired
       (sort-by key)
       (keep (fn [[id version]]
               (when (not= version (get installed id))
                 {:id id :version version :installed-version (get installed id)})))
       vec))

(defn run-cursor!
  "Runs the Cursor CLI with separate arguments and captures both output streams."
  [executable args]
  (select-keys @(process/process (into [executable] args) {:out :string :err :string})
               [:exit :out :err]))

(defn- installed
  [executable allow-empty?]
  (let [{:keys [exit out err]} (run-cursor! executable ["--list-extensions" "--show-versions"])]
    (when-not (zero? exit)
      (fail! (str "Cursor extension listing failed: " err) {:exit exit}))
    (if (and allow-empty? (string/blank? out))
      (sorted-map)
      (parse-manifest out))))

(defn- read-manifest
  "Reads a pinned extension manifest, failing if the file is missing."
  [file]
  (let [path (str (fs/absolutize (fs/expand-home file)))]
    (when-not (fs/exists? path)
      (fail! (str "Manifest file not found: " path) {:file path}))
    (parse-manifest (slurp path))))

(defn- write-manifest!
  [file manifest]
  (let [target (fs/absolutize (fs/expand-home file))
        temporary (fs/create-temp-file {:dir (fs/parent target) :prefix ".cursor-sync-"})]
    (try
      (spit (str temporary) (render-manifest manifest))
      (fs/move temporary target {:replace-existing true :atomic-move true})
      (finally
        (fs/delete-if-exists temporary)))))

(defn- export!
  [executable file dry-run?]
  (let [manifest (installed executable false)]
    (if dry-run?
      (do
        (println "Would export" (count manifest) "extensions to" file)
        (print (render-manifest manifest))
        {:dry-run true :exported (count manifest) :file file})
      (do
        (write-manifest! file manifest)
        (println "Exported" (count manifest) "extensions to" file)
        {:exported (count manifest) :file file}))))

(defn- print-plan!
  [plan]
  (doseq [{:keys [id version installed-version]} plan]
    (println (str id ": " (or installed-version "not installed") " -> " version)))
  (when (empty? plan)
    (println "All pinned extensions already match.")))

(defn- install-one!
  [executable {:keys [id version] :as entry}]
  (let [pinned (str id "@" version)]
    (try
      (let [{:keys [exit out err]} (run-cursor! executable ["--install-extension" pinned "--force"])]
        (when-not (zero? exit)
          (let [message (str "Failed to install " pinned ": " (string/trim (str err "\n" out)))]
            (binding [*out* *err*] (println message))
            (assoc entry :error message))))
      (catch Exception exception
        (let [message (str "Failed to install " pinned ": " (ex-message exception))]
          (binding [*out* *err*] (println message))
          (assoc entry :error message))))))

(defn- apply-plan!
  [executable desired plan]
  (let [failures (into [] (keep #(install-one! executable %)) plan)
        remaining (import-plan desired (installed executable true))]
    (doseq [{:keys [id version installed-version]} remaining]
      (binding [*out* *err*]
        (println (str "Version mismatch for " id ": requested " version
                      ", installed " (or installed-version "none")))))
    (when (or (seq failures) (seq remaining))
      (fail! "Some pinned extensions could not be installed. Obtain unavailable versions as VSIX files and install them manually; no fallback versions were requested."
             {:failures failures :remaining remaining}))
    (println "Verified all" (count desired) "pinned extension versions.")
    {:installed (count plan) :verified (count desired)}))

(defn- import!
  [executable file dry-run?]
  (let [desired (read-manifest file)
        plan (import-plan desired (installed executable true))]
    (print-plan! plan)
    (if dry-run?
      {:dry-run true :plan plan}
      (apply-plan! executable desired plan))))

(defn- options
  [argv]
  (let [{:keys [opts args]} (cli/parse-args argv cli-spec)
        unknown (remove (set (keys (:coerce cli-spec))) (keys opts))]
    (when (or (seq unknown) (seq args))
      (fail! (str "Unknown options or arguments: " (pr-str (concat unknown args))) {}))
    (doseq [option [:file :cursor]]
      (when (and (contains? opts option) (string/blank? (get opts option)))
        (fail! (str "--" (name option) " requires a value.") {})))
    (when (and (not (:help opts)) (= (boolean (:export opts)) (boolean (:import opts))))
      (fail! "Choose exactly one of --export or --import." {}))
    opts))

(defn- cursor-executable
  [configured]
  (or (some-> configured fs/expand-home str)
      (let [bundled "/Applications/Cursor.app/Contents/Resources/app/bin/cursor"]
        (when (fs/exists? bundled) bundled))
      (some-> (fs/which "cursor") str)
      (fail! "Cursor CLI not found. Supply --cursor /path/to/cursor." {})))

(defn exec!
  "Exports or applies a version-pinned Cursor extension manifest without Git operations."
  [argv]
  (let [{:keys [help export apply file cursor]} (options argv)]
    (if help
      (println (str "bbg cursor-sync --export | --import [--apply]\n"
                    "  --file PATH     Manifest (default: ~/Library/Application Support/Cursor/User/extensions.txt)\n"
                    "  --cursor PATH   Cursor CLI executable\n"
                    "  --apply         Write the manifest or install pinned versions\n"
                    "  --help          Show this help\n\n"
                    "Export records exact installed versions. Import installs missing/different versions,\n"
                    "including downgrades, and retains extra local extensions. Dry-run is the default.\n"
                    "Unavailable pinned versions require manual VSIX installation; no version fallback.\n"
                    "Commit/push and pull the manifest separately. This task performs no Git operations."))
      (let [executable (cursor-executable cursor)
            manifest-file (or file (str (fs/path (System/getProperty "user.home")
                                                "Library/Application Support/Cursor/User/extensions.txt")))
            dry-run? (not apply)]
        (if export
          (export! executable manifest-file dry-run?)
          (import! executable manifest-file dry-run?))))))
