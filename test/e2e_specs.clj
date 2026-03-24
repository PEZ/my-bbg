(ns e2e-specs
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def cache-dir "dev/test-specs/md_cases")

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
  [toml-string file-name]
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
    {:file file-name
     :given-md given-md
     :given-files given-files
     :expectations expectations}))

(defn download-specs!
  "Download TOML spec files from GitHub. Skips existing unless refresh? is true."
  [& {:keys [refresh?]}]
  (let [api-url "https://api.github.com/repos/yshavit/mdq/contents/tests/md_cases"
        resp (http/get api-url {:headers {"Accept" "application/vnd.github.v3+json"}})
        items (json/parse-string (:body resp) true)
        toml-files (filter #(str/ends-with? (:name %) ".toml") items)]
    (fs/create-dirs cache-dir)
    (doseq [{:keys [name download_url]} toml-files
            :let [target (fs/path cache-dir name)]
            :when (or refresh? (not (fs/exists? target)))]
      (let [resp (http/get download_url)]
        (spit (str target) (:body resp))
        (println "Downloaded" name)))
    (println (str "Specs cached in " cache-dir " (" (count (fs/glob cache-dir "*.toml")) " files)"))))

(defn load-specs
  "Load and parse specs from cache. Optional spec-file to load just one."
  [& {:keys [spec-file]}]
  (let [files (if spec-file
                [(fs/path cache-dir spec-file)]
                (sort (fs/glob cache-dir "*.toml")))]
    (mapv (fn [f]
            (parse-spec (slurp (str f)) (str (fs/file-name f))))
          files)))

(defn ensure-specs!
  "Download specs if cache is empty, otherwise use cache."
  [& {:keys [refresh?]}]
  (when (or refresh? (empty? (fs/glob cache-dir "*.toml")))
    (download-specs! :refresh? refresh?)))
