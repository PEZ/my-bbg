(ns e2e-specs
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def specs-root-dir "dev/test-specs")

(def upstream-cache-dir (str (fs/path specs-root-dir "md_cases")))

(def local-specs-dir (str (fs/path specs-root-dir "local")))

(defn- normalize-spec-file
  [spec-file]
  (let [root-prefix (re-pattern (str "^"
                                     (java.util.regex.Pattern/quote specs-root-dir)
                                     "/"))]
    (some-> spec-file
            (str/replace #"^\./" "")
            (str/replace root-prefix ""))))

(defn- spec-path->label
  [spec-path]
  (str (fs/relativize specs-root-dir spec-path)))

(defn- spec-files
  "List spec files. Local specs override upstream specs with the same filename."
  []
  (let [all (->> [upstream-cache-dir local-specs-dir]
                 (filter fs/directory?)
                 (mapcat #(fs/glob % "*.toml"))
                 (map (fn [path]
                        {:label (spec-path->label path)
                         :name (str (fs/file-name path))
                         :path path})))
        local-names (->> all
                         (filter #(str/starts-with? (:label %) "local/"))
                         (map :name)
                         set)]
    (->> all
         (remove (fn [{:keys [label name]}]
                   (and (not (str/starts-with? label "local/"))
                        (local-names name))))
         (sort-by :label)
         vec)))

(defn- matching-spec-files
  [spec-file]
  (let [selector (normalize-spec-file spec-file)
        files (spec-files)]
    (if-not selector
      files
      (let [matches (filterv (fn [{:keys [label name]}]
                               (or (= label selector)
                                   (= name selector)))
                             files)]
        (when-not (seq matches)
          (throw (ex-info (str "No E2E specs matched: " spec-file)
                          {:spec-file spec-file
                           :available (mapv :label files)})))
        matches))))

(defn- parse-toml
  "Parse a TOML string via Python 3.11+ tomllib. Returns Clojure map."
  [toml-string]
  (-> (p/shell {:in toml-string :out :string}
               "python3" "-c"
               "import sys, tomllib, json; print(json.dumps(tomllib.loads(sys.stdin.read())))")
      :out
      (json/parse-string true)))

(defn parse-spec
  "Parse a TOML spec string into canonical data shape.
   Returns {:file :given-md :given-files :expectations [...]}"
  [toml-string file-label]
  (let [parsed (parse-toml toml-string)
        given-md (get-in parsed [:given :md])
        given-files (get-in parsed [:given :files])
        expects (:expect parsed)
        expectations (->> expects
                          (mapv (fn [[test-name data]]
                                  {:name (name test-name)
                                   :cli-args (:cli_args data)
                                   :expected-output (:output data)
                                   :expect-success (get data :expect_success true)
                                   :output-json (boolean (:output_json data))
                                   :output-err (:output_err data)
                                   :ignore (:ignore data)})))]
                    {:file file-label
     :given-md given-md
     :given-files given-files
     :expectations expectations}))

(defn download-specs!
  "Download upstream TOML spec files from GitHub into the cache corpus.
   Skips existing unless refresh? is true."
  [& {:keys [refresh?]}]
  (let [api-url "https://api.github.com/repos/yshavit/mdq/contents/tests/md_cases"
        resp (http/get api-url {:headers {"Accept" "application/vnd.github.v3+json"}})
        items (json/parse-string (:body resp) true)
        toml-files (filter #(str/ends-with? (:name %) ".toml") items)]
    (fs/create-dirs upstream-cache-dir)
    (doseq [{:keys [name download_url]} toml-files
            :let [target (fs/path upstream-cache-dir name)]
            :when (or refresh? (not (fs/exists? target)))]
      (let [resp (http/get download_url)]
        (spit (str target) (:body resp))
        (println "Downloaded" name)))
    (println (str "Specs cached in " upstream-cache-dir
                  " (" (count (fs/glob upstream-cache-dir "*.toml")) " files)"))))

(defn load-specs
  "Load and parse specs from the upstream cache and local corpus.
   Optional spec-file may be a basename or a path relative to dev/test-specs."
  [& {:keys [spec-file]}]
  (mapv (fn [{:keys [label path]}]
          (parse-spec (slurp (str path)) label))
        (matching-spec-files spec-file)))

(defn ensure-specs!
  "Download upstream specs if the upstream cache is empty, otherwise use cache."
  [& {:keys [refresh?]}]
  (when (or refresh? (empty? (fs/glob upstream-cache-dir "*.toml")))
    (download-specs! :refresh? refresh?)))
