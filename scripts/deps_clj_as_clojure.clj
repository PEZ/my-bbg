(ns deps-clj-as-clojure
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private repo "borkdude/deps.clj")
(def ^:private binaries-dir (fs/path (System/getProperty "user.home") "binaries"))
(def ^:private bin-dir (fs/path (System/getProperty "user.home") "bin"))
(def ^:private binary-name "deps")

(defn- binary-path []
  (fs/path binaries-dir binary-name))

(defn- asset-name [version]
  (let [os (if (= "Mac OS X" (System/getProperty "os.name")) "macos" "linux")]
    (str "deps.clj-" version "-" os "-amd64.zip")))

(defn- latest-release []
  (let [response (http/get (str "https://api.github.com/repos/" repo "/releases/latest")
                           {:headers {"Accept" "application/vnd.github.v3+json"}})
        {:keys [tag_name assets]} (json/parse-string (:body response) true)
        version (subs tag_name 1)]
    {:version version
     :assets (into {} (map (juxt :name :browser_download_url)) assets)}))

(defn- current-version []
  (when (fs/exists? (binary-path))
    (let [{:keys [out]} @(p/process [(str (binary-path)) "--version"]
                                    {:out :string :err :string})]
      (some->> out str/trim (re-find #"[\d.]+$")))))

(defn update! [_opts]
  (let [{:keys [version assets]} (latest-release)
        asset-key (asset-name version)
        download-url (get assets asset-key)]
    (when-not download-url
      (println (str "Error: No asset found for " asset-key))
      (println "Available:" (keys assets))
      (System/exit 1))
    (let [current (current-version)]
      (println (str "Latest:  " version))
      (println (str "Current: " (or current "(not installed)")))
      (when (= current version)
        (println "Already up to date.")
        (System/exit 0)))
    (fs/create-dirs binaries-dir)
    (let [zip-path (fs/path binaries-dir (str binary-name ".zip"))]
      (println (str "Downloading " asset-key "..."))
      (io/copy (:body (http/get download-url {:as :stream}))
               (io/file (str zip-path)))
      (println "Extracting...")
      (fs/unzip zip-path binaries-dir {:replace-existing true})
      (fs/delete zip-path)
      (fs/set-posix-file-permissions (binary-path) "rwxr-xr-x")
      (println (str "Installed to " (binary-path))))))

(defn use! [_opts]
  (when-not (fs/exists? (binary-path))
    (println (str "Error: " (binary-path) " not found. Run deps-clj:update first."))
    (System/exit 1))
  (doseq [link-name ["clojure" "clj"]]
    (let [link-path (fs/path bin-dir link-name)]
      (when (fs/exists? link-path)
        (fs/delete link-path))
      (fs/create-sym-link link-path (binary-path))
      (println (str "Linked " link-path " -> " (binary-path))))))

(defn unuse! [_opts]
  (doseq [link-name ["clojure" "clj"]]
    (let [link-path (fs/path bin-dir link-name)]
      (if (and (fs/exists? link-path) (fs/sym-link? link-path))
        (do (fs/delete link-path)
            (println (str "Removed " link-path)))
        (println (str "Skipping " link-path " (not a symlink)"))))))
