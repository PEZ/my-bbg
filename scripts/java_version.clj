(ns java-version
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as string]))

(def ^:private sdkman-java-dir
  (str (System/getProperty "user.home") "/.sdkman/candidates/java"))

(defn- parse-major
  "Extract the major version number from an sdkman identifier like '21.0.1.fx-zulu'."
  [identifier]
  (some-> (re-find #"^(\d+)" identifier)
          second
          parse-long))

(defn- installed-identifiers
  "List all installed JDK identifiers from sdkman's filesystem."
  []
  (->> (fs/list-dir sdkman-java-dir)
       (map #(str (fs/file-name %)))
       (remove #{"current"})
       (filter #(re-matches #"\d+.*" %))
       sort))

(defn- current-identifier []
  (let [link (fs/read-link (fs/path sdkman-java-dir "current"))]
    (str (fs/file-name link))))

(defn- version-sort-key
  "Parse version string for numeric sorting. '21.0.10' -> [21 0 10]"
  [identifier]
  (let [version-part (first (string/split identifier #"-"))]
    (->> (string/split version-part #"[.\-+]")
         (keep #(parse-long %))
         vec)))

(defn- best-installed
  "Find the best installed identifier for a given major version.
   Prefers graal > graalce > any other vendor. Among same vendor tier, picks highest version."
  [major identifiers]
  (let [matching (->> identifiers
                      (filter #(= major (parse-major %))))
        vendor-pref (fn [id]
                      (cond
                        (string/ends-with? id "-graal") 0
                        (string/ends-with? id "-graalce") 1
                        :else 2))]
    (->> matching
         (sort-by (fn [id] [(vendor-pref id) (mapv - (version-sort-key id))]))
         first)))

(defn- highest-installed-major [identifiers]
  (->> identifiers
       (keep parse-major)
       (apply max)))

(defn- sdk-list-available
  "Parse 'sdk list java' output to find available identifiers."
  []
  (try
    (let [output (-> (p/shell {:out :string :err :string :continue true}
                              "bash" "-c"
                              "source \"$HOME/.sdkman/bin/sdkman-init.sh\" && sdk list java")
                     :out)]
      (->> (string/split-lines output)
           (keep #(re-find #"\|\s+([\w.\-]+)\s*$" %))
           (map second)))
    (catch Exception _
      [])))

(defn- best-available
  "Find the best available (not yet installed) identifier for a major version."
  [major]
  (let [available (sdk-list-available)
        matching (->> available
                      (filter #(= major (parse-major %))))
        vendor-pref (fn [id]
                      (cond
                        (string/ends-with? id "-graal") 0
                        (string/ends-with? id "-graalce") 1
                        :else 2))]
    (->> matching
         (sort-by (fn [id] [(vendor-pref id) (mapv - (version-sort-key id))]))
         first)))

(defn- sdk-cmd [subcmd identifier]
  (str "sdk " subcmd " java " identifier))

(defn status! []
  (let [installed (installed-identifiers)
        current (current-identifier)]
    (println (str "Current: " current " (java " (parse-major current) ")"))
    (println)
    (println "Installed:")
    (doseq [id installed]
      (println (str "  " (if (= id current) "→ " "  ") id)))
    (println)
    (println "Usage: bbg java <major-version|latest>")))

(defn switch! [{:keys [version]}]
  (let [installed (installed-identifiers)
        major (if (= version "latest")
                (highest-installed-major installed)
                (parse-long version))]
    (when-not major
      (println (str "✗ Invalid version: " version))
      (System/exit 1))
    (let [current (current-identifier)]
      (when (= major (parse-major current))
        (println (str "Already on java " major " (" current ")"))
        (System/exit 0)))
    (if-let [id (best-installed major installed)]
      (do (println (str "Found installed: " id))
          (println "Command line to execute:")
          (println " " (sdk-cmd "use" id)))
      (do (println (str "No java " major " installed. Looking up available..."))
          (if-let [id (best-available major)]
            (do (println (str "Best available: " id))
                (println "Command line to execute:")
                (println " " (sdk-cmd "install" id)))
            (do (println (str "✗ No java " major " found in SDKMAN"))
                (System/exit 1)))))))


(def ^:export cli-spec
  {:coerce {:status :boolean
            :version :string}
   :args->opts [:version]})

(defn exec! [{:keys [status version] :as opts}]
  (when-not (fs/exists? sdkman-java-dir)
    (println "✗ SDKMAN not found at" sdkman-java-dir)
    (System/exit 1))
  (cond
    status (status!)
    version (switch! opts)
    :else (status!)))
