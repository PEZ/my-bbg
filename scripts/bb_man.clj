(ns bb-man
  (:require [babashka.http-client :as http]
            [babashka.process :as p]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; --- Config ---





;; --- Gather (impure but safe) ---











;; --- Transform (pure) ---





;; --- Validate & Resolve ---



;; --- Find runs & artifacts ---









;; --- Act (side effects) ---





;; --- Config ---

(def ^:private bb-repo "babashka/babashka")
(def ^:private bb-api (str "https://api.github.com/repos/" bb-repo))
(def ^:private build-workflow-id 900192)
(def ^:private home-bb (str (fs/home) "/bin/bb"))

;; --- Gather (impure but safe) ---

(defn- gh-token []
  (-> (p/shell {:out :string} "gh" "auth" "token")
      :out
      str/trim))

(defn- gh-get
  [url & [query-params]]
  (-> (http/get url
                {:headers {"Accept" "application/vnd.github+json"
                           "Authorization" (str "Bearer " (gh-token))}
                 :query-params (or query-params {})})
      :body
      (json/parse-string true)))

(defn- gh-get-safe
  "Like gh-get but returns nil on 404/422 instead of throwing."
  [url & [query-params]]
  (let [resp (http/get url
                       {:headers {"Accept" "application/vnd.github+json"
                                  "Authorization" (str "Bearer " (gh-token))}
                        :query-params (or query-params {})
                        :throw false})]
    (when (<= 200 (:status resp) 299)
      (json/parse-string (:body resp) true))))

(defn- bb-version [bb-path]
  (-> (p/shell {:out :string} bb-path "--version") :out str/trim))

(defn- latest-release []
  (gh-get (str bb-api "/releases/latest")))

(defn- latest-release-tag []
  (:tag_name (latest-release)))

(defn- system-bb-path []
  (let [home-bin (str (fs/home) "/bin")
        path-dirs (str/split (System/getenv "PATH") #":")
        non-home-dirs (remove #{home-bin} path-dirs)]
    (->> non-home-dirs
         (some (fn [dir]
                 (let [candidate (str dir "/bb")]
                   (when (fs/exists? candidate) candidate)))))))

(defn find-workflow-id
  "Looks up a workflow ID by its .yml path (e.g. \".github/workflows/build.yml\")."
  [workflow-path]
  (->> (gh-get (str bb-api "/actions/workflows"))
       :workflows
       (filter #(= (:path %) workflow-path))
       first
       :id))

;; --- Transform (pure) ---

(defn classify-ref
  "Classifies a git ref string into {:type ...}.
   Recognizes :latest, :pr, :sha, and :branch-or-tag."
  [ref]
  (cond
    (= ref "latest") {:type :latest}
    (re-matches #"#\d+" ref) {:type :pr :number (subs ref 1)}
    (re-matches #"\d+" ref) {:type :pr :number ref}
    (re-matches #"[0-9a-f]{7,40}" ref) {:type :sha :sha ref}
    :else {:type :branch-or-tag :name ref}))

(defn- aarch64-artifact [artifacts]
  (->> artifacts
       (filter #(str/includes? (:name %) "macos-aarch64"))
       first))

;; --- Validate & Resolve ---

(defn resolve-ref
  "Validates that a git ref exists and resolves it to a SHA.
   Returns {:sha :ref-type :ref :description} or throws with a helpful message."
  [ref]
  (let [{:keys [type] :as classified} (classify-ref ref)]
    (case type
      :latest
      (resolve-ref (latest-release-tag))

      :pr
      (if-let [pr-data (gh-get-safe (str bb-api "/pulls/" (:number classified)))]
        {:sha (get-in pr-data [:head :sha])
         :ref-type :pr
         :ref ref
         :description (str "PR #" (:number classified) ": " (:title pr-data))}
        (throw (ex-info (str "PR #" (:number classified) " not found in " bb-repo)
                        {:ref ref :type type})))

      :sha
      (if-let [commit (gh-get-safe (str bb-api "/commits/" (:sha classified)))]
        {:sha (:sha commit)
         :ref-type :sha
         :ref ref
         :description (str "commit " (subs (:sha commit) 0 12))}
        (throw (ex-info (str "Commit " (:sha classified) " not found in " bb-repo)
                        {:ref ref :type type})))

      :branch-or-tag
      (if-let [commit (gh-get-safe (str bb-api "/commits/" (:name classified)))]
        {:sha (:sha commit)
         :ref-type :branch-or-tag
         :ref ref
         :description (str "ref '" (:name classified) "' at " (subs (:sha commit) 0 12))}
        (throw (ex-info (str "Branch or tag '" (:name classified) "' not found in " bb-repo)
                        {:ref ref :type type}))))))

;; --- Find runs & artifacts ---

(defn- find-runs-by-branch [branch]
  (:workflow_runs
   (gh-get (str bb-api "/actions/workflows/" build-workflow-id "/runs")
           {"branch" branch
            "per_page" "1"
            "status" "completed"})))

(defn- find-runs-by-sha [sha]
  (:workflow_runs
   (gh-get (str bb-api "/actions/workflows/" build-workflow-id "/runs")
           {"head_sha" sha})))

(defn find-latest-run
  "Finds the latest completed build run for a resolved ref.
   Takes the result of resolve-ref."
  [{:keys [sha ref-type ref]}]
  (let [runs (if (= ref-type :branch-or-tag)
               (let [branch-runs (find-runs-by-branch ref)]
                 (if (seq branch-runs)
                   branch-runs
                   (find-runs-by-sha sha)))
               (find-runs-by-sha sha))]
    (first runs)))

(defn find-macos-silicon-artifact
  "Finds the macOS Silicon (aarch64) artifact for a given git ref.
   Validates the ref exists before querying. Throws on invalid ref or missing artifact."
  [ref]
  (let [resolved (resolve-ref ref)
        run (find-latest-run resolved)]
    (when-not run
      (throw (ex-info (str "No completed build found for " (:description resolved))
                      {:ref ref :resolved resolved})))
    (let [artifact (->> (gh-get (str bb-api "/actions/runs/" (:id run) "/artifacts"))
                        :artifacts
                        aarch64-artifact)]
      (when-not artifact
        (throw (ex-info (str "No macOS Silicon artifact found for " (:description resolved)
                             " (run " (:id run) ")")
                        {:ref ref :run-id (:id run)})))
      {:ref ref
       :description (:description resolved)
       :run-id (:id run)
       :run-url (:html_url run)
       :sha (subs (:head_sha run) 0 12)
       :branch (:head_branch run)
       :artifact (:name artifact)
       :size-mb (format "%.1f" (/ (:size_in_bytes artifact) 1048576.0))
       :download-url (:archive_download_url artifact)
       :expired (:expired artifact)})))

;; --- Act (side effects) ---

(defn download-bb!
  "Downloads the macOS Silicon bb binary for the given ref to /tmp/bbg/.
   Returns the path to the downloaded binary, or nil if not found."
  [ref]
  (let [{:keys [run-id artifact]} (find-macos-silicon-artifact ref)
        dest-dir "/tmp/bbg"
        bb-path (fs/path dest-dir "bb")]
    (fs/create-dirs dest-dir)
    (fs/delete-if-exists bb-path)
    (let [{:keys [exit]} (p/shell {:continue true :dir dest-dir}
                                  "gh" "run" "download" (str run-id)
                                  "-R" bb-repo
                                  "-n" artifact)]
      (when (and (zero? exit) (fs/exists? bb-path))
        (str bb-path)))))

;; --- Task operations ---

(defn- status! []
  (let [has-home-bb (fs/exists? home-bb)
        which-bb (str (fs/which "bb"))
        system-bb (system-bb-path)
        home-bb-active? (and has-home-bb (= which-bb home-bb))
        latest (latest-release)
        master-sha (-> (resolve-ref "master") :sha (subs 0 12))]
    (println "bb status:")
    (println (str "  Active:    " (bb-version which-bb) " (" which-bb ")"))
    (when (and has-home-bb (not home-bb-active?))
      (println (str "  ~/bin/bb:  " (bb-version home-bb) " (shadowed by " which-bb ")")))
    (when home-bb-active?
      (println (str "  System:    " (bb-version system-bb) " (" system-bb ")")))
    (println (str "  Latest:    " (:tag_name latest)))
    (println (str "  Master:    " master-sha))))

(defn- download! [ref]
  (println (str "Downloading bb for ref '" ref "'..."))
  (let [path (download-bb! ref)]
    (if path
      (do (println (str "Downloaded to: " path))
          (println (str "  Version: " (bb-version path)))
          path)
      (do (println "Download failed.")
          nil))))

(defn- use! [ref]
  (let [downloaded (download! ref)]
    (when downloaded
      (fs/create-dirs (str (fs/home) "/bin"))
      (fs/copy downloaded home-bb {:replace-existing true})
      (fs/set-posix-file-permissions home-bb "rwxr-xr-x")
      (println (str "Installed to: " home-bb))
      (println (str "  Version: " (bb-version home-bb)))
      home-bb)))

(defn- unuse! []
  (if (fs/exists? home-bb)
    (let [dest "/tmp/bbg/uninstalled-bb"]
      (fs/create-dirs "/tmp/bbg")
      (fs/delete-if-exists dest)
      (fs/move home-bb dest)
      (println (str "Moved " home-bb " to " dest))
      (println (str "System bb is now active: " (fs/which "bb")))
      dest)
    (do (println (str "No bb found at " home-bb " — nothing to uninstall."))
        nil)))

;; --- CLI ---

(def cli-spec
  {:coerce {:download :string
            :use :string
            :unuse :boolean}})

(defn exec! [{:keys [download use unuse]}]
  (cond
    download (download! download)
    use      (use! use)
    unuse    (unuse!))
  (status!))

(comment
  ;; Classify refs (pure, no API calls)
  (classify-ref "latest")
  (classify-ref "master")
  (classify-ref "v1.12.217")
  (classify-ref "44d1c0dd")
  (classify-ref "#1958")

  ;; Validate & resolve refs
  (resolve-ref "latest")
  (resolve-ref "master")
  (resolve-ref "v1.12.217")
  (resolve-ref "#1958")
  (try (resolve-ref "nonexistent-xyz")
       (catch Exception e (ex-message e)))

  ;; Find artifact info
  (find-macos-silicon-artifact "latest")
  (find-macos-silicon-artifact "master")
  (find-macos-silicon-artifact "v1.12.217")

  ;; Look up workflow IDs by path
  (find-workflow-id ".github/workflows/build.yml")

  ;; Task operations
  (exec! {})
  (exec! {:download "latest"})
  (exec! {:use "latest"})
  (exec! {:unuse true})

  ;; Download to /tmp/bbg/
  (download-bb! "latest")
  (download-bb! "master")

  ;; Verify downloaded binary
  (-> (p/shell {:out :string} "/tmp/bbg/bb" "--version")
      :out str/trim)

  :rcf)
