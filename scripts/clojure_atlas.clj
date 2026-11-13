(ns clojure-atlas
  "Builds clojure-atlas graphs, and keeps one clojure-atlas serve running for every repo a window
  has registered, started again when a window registers another repo."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http-client]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def cli-spec
  {:coerce {:repo :string
            :port :long}})

(def default-port 7979)

(def max-repos
  "How many repos one clojure-atlas serve takes."
  32)

(def atlas-command ["clojure-atlas"])

(defn served-repos
  "The repos to register with clojure-atlas serve, from the `registry` of repo → when it was last
  registered: those that still exist according to `exists?`, at most as many as clojure-atlas
  takes, the most recently registered first, then sorted."
  [registry exists?]
  (->> registry
       (filter (comp exists? key))
       (sort-by val >)
       (take max-repos)
       (map key)
       sort
       vec))

(defn serve-command [atlas repos port]
  (into atlas (concat ["serve" "--port" (str port)]
                      (mapcat (fn [repo] ["--repo" repo]) repos))))

;; The registry: the repos windows have asked to have served, with when each last asked

(defn- cache-dir []
  (str (fs/path (or (System/getenv "XDG_CACHE_HOME") (fs/path (fs/home) ".cache")) "clojure-atlas")))

(defn registry-file [] (str (fs/path (cache-dir) "bbg-repos.edn")))

(defn token-file
  "Where clojure-atlas serve keeps its bearer token, which it creates on its first start."
  []
  (str (fs/path (cache-dir) "http-token")))

(defn- read-registry []
  (let [f (registry-file)]
    (or (when (fs/exists? f)
          (try (edn/read-string (slurp f)) (catch Exception _ nil)))
        {})))

(defn register!
  "Adds `repo` to the registry, so the serving process starts clojure-atlas again with it."
  [repo]
  (let [repo (str (fs/canonicalize repo))
        f (registry-file)
        tmp (str f "." (random-uuid) ".partial")]
    (fs/create-dirs (fs/parent f))
    (spit tmp (pr-str (assoc (read-registry) repo (System/currentTimeMillis))))
    (fs/move tmp f {:replace-existing true :atomic-move true})
    repo))

(defn- current-repos [] (served-repos (read-registry) fs/directory?))

;; Serving: the process that holds the port runs clojure-atlas serve; the others stand by

(defonce ^:private running (atom nil))

(defn- port-free? [port]
  (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] false)
       (catch java.net.ConnectException _ true)))

(defn- atlas-on?
  "Whether clojure-atlas holds `port`. It answers a request without the token with a challenge
  that names it."
  [port]
  (try (-> (http-client/get (str "http://127.0.0.1:" port "/status") {:throw false :timeout 2000})
           (get-in [:headers "www-authenticate"] "")
           (str/includes? "clojure-atlas"))
       (catch Exception _ false)))

(defn- start-atlas! [repos port]
  (let [proc (p/process (serve-command atlas-command repos port) {:err :pipe :out :inherit})
        serving (promise)]
    (future
      (with-open [r (io/reader (:err proc))]
        (doseq [line (line-seq r)]
          (println line)
          (when (str/starts-with? line "clojure-atlas: serving")
            (deliver serving true))))
      (deliver serving false))
    (reset! running {:proc proc :repos repos :serving serving})))

(defn- stop! [{:keys [proc]}]
  (p/destroy proc)
  @proc)

(defn- hold!
  "Runs clojure-atlas serve on `port` with the registered repos, and starts it again when the
  registry changes. Returns when clojure-atlas stops, true if it served, and false if it never
  started serving, such as when it couldn't take the port."
  [port]
  (loop [{:keys [proc repos serving] :as atlas} (start-atlas! (current-repos) port)]
    (if-not (deref serving 60000 false)
      (do (stop! atlas)
          false)
      (do (Thread/sleep 2000)
          (cond
            (not (p/alive? proc)) true
            (not= repos (current-repos)) (do (stop! atlas)
                                             (recur (start-atlas! (current-repos) port)))
            :else (recur atlas))))))

(defn- standby-message [port]
  (cond
    (atlas-on? port) (str "clojure-atlas: standing by; another clojure-atlas serves on port " port
                          ", and this one takes over when it stops")
    (empty? (current-repos)) "clojure-atlas: standing by; no repos are registered yet"
    :else (str "clojure-atlas: standing by; something else holds port " port)))

(defn serve!
  "Registers `repo`, then serves the registered repos until the process is stopped. While another
  process holds `port`, this one stands by, and takes over when that process stops, so the server
  stays up as long as any window runs its serve task."
  [{:keys [repo port] :or {port default-port}}]
  (when repo
    (println "clojure-atlas: registered" (register! repo)))
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(some-> @running stop!)))
  (loop [standing-by? false]
    (if (and (port-free? port) (seq (current-repos)))
      (let [served? (hold! port)]
        (if (or served? (not (port-free? port)))
          (Thread/sleep 2000)
          (do (println "clojure-atlas: clojure-atlas serve did not start; trying again in 30 s")
              (Thread/sleep 30000)))
        (recur false))
      (do (when-not standing-by?
            (println (standby-message port)))
          (Thread/sleep 2000)
          (recur true)))))

(defn headers!
  "Prints the Authorization header MCP clients send, as JSON, for a client setting such as Claude
  Code's headersHelper."
  []
  (let [f (token-file)]
    (if (fs/exists? f)
      (println (json/generate-string {"Authorization" (str "Bearer " (str/trim (slurp f)))}))
      (binding [*out* *err*]
        (println (str "No clojure-atlas token at " f "; start the serve task first"))
        (System/exit 1)))))

(defn build!
  "Builds the graph of `repo`, the current directory by default."
  [{:keys [repo]}]
  (let [{:keys [exit]} (p/shell {:dir (str (fs/absolutize (or repo "."))) :continue true}
                                "clojure-atlas" "build")]
    (System/exit exit)))

(defn exec! [{:keys [args opts]}]
  (case (first args)
    "build" (build! opts)
    "serve" (serve! opts)
    "headers" (headers!)
    (do (println "Usage: bbg clojure-atlas build [--repo DIR]")
        (println "       bbg clojure-atlas serve [--repo DIR] [--port 7979]")
        (println "       bbg clojure-atlas headers")
        (println "MCP clients connect to http://127.0.0.1:<port>/mcp?repo=<absolute repo path>,")
        (println "sending the Authorization header that `headers` prints."))))

(comment
  (current-repos)
  (register! ".")
  (def serving (future (serve! {:repo "." :port 7980})))
  (stop! @running)
  :rcf)
