(ns bb-man
  (:require [babashka.http-client :as http]
            [babashka.process :as p]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; --- Config ---

(def ^:private bb-repo "babashka/babashka")
(def ^:private bb-api (str "https://api.github.com/repos/" bb-repo))
(def ^:private build-workflow-id 900192)

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

(defn find-workflow-id
  "Looks up a workflow ID by its .yml path (e.g. \".github/workflows/build.yml\")."
  [workflow-path]
  (->> (gh-get (str bb-api "/actions/workflows"))
       :workflows
       (filter #(= (:path %) workflow-path))
       first
       :id))

(comment
  (find-workflow-id ".github/workflows/build.yml")
  :rcf)

;; --- Transform (pure) ---

(defn classify-ref
  "Classifies a git ref string into {:type :pr/:sha/:branch-or-tag ...}."
  [ref]
  (cond
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

(comment

  ;; Classify refs (pure, no API calls)
  (classify-ref "master")
  (classify-ref "v1.12.217")
  (classify-ref "44d1c0dd")
  (classify-ref "#1958")

  ;; Validate & resolve refs
  (resolve-ref "master")
  (resolve-ref "v1.12.217")
  (resolve-ref "#1958")
  (try (resolve-ref "nonexistent-xyz")
       (catch Exception e (ex-message e)))

  ;; Find artifact info for various ref types
  (find-macos-silicon-artifact "master")
  (find-macos-silicon-artifact "v1.12.217")
  (find-macos-silicon-artifact "44d1c0dd")
  (find-macos-silicon-artifact "#1958")

  ;; Download to /tmp/bbg/
  (download-bb! "master")
  (download-bb! "v1.12.217")
  (download-bb! "44d1c0dd")
  (download-bb! "deadbeef")
  (download-bb! "#1958")

  ;; Verify downloaded binary
  (-> (p/shell {:out :string} "/tmp/bbg/bb" "--version")
      :out str/trim)

  :rcf)
