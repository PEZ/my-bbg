(ns clojure-atlas
  "Builds clojure-atlas graphs, and serves them to MCP clients over HTTP. Each graph gets one
  clojure-atlas process, shared by every session that asks about it, and started again when the
  graph is rebuilt."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http-client]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http])
  (:import (java.net URLDecoder)))

(def cli-spec
  {:coerce {:repo :string
            :port :long}})

(def default-port 7979)

(def settle-ms
  "How long a rebuilt graph file must be left alone before a server starts on it, so a build that
  is still writing it is not read halfway."
  1500)

(def call-timeout-ms 120000)

(defn graph-file [repo]
  (str (fs/path repo ".clojure-atlas" "graph.json.gz")))

(defn- stamp
  "When the graph file was last written, in epoch ms, or nil when there is no graph."
  [graph]
  (when (fs/exists? graph)
    (.toMillis (fs/last-modified-time graph))))

(defn stale?
  "Whether a server started on the graph written at `served` should start again for the graph
  written at `current`, when the time is `now`."
  [served current now]
  (boolean (and current
                (not= served current)
                (< settle-ms (- now current)))))

(defn query-param [query-string k]
  (some (fn [pair]
          (let [[pk v] (str/split pair #"=" 2)]
            (when (= k pk)
              (URLDecoder/decode (or v "") "UTF-8"))))
        (some-> query-string (str/split #"&"))))

(defn foreign-origin?
  "Whether a request comes from a web page other than a local one. MCP over HTTP asks servers to
  refuse those, so a site in a browser can't reach a local server."
  [origin]
  (boolean (and origin
                (not (re-matches #"https?://(localhost|127\.0\.0\.1)(:\d+)?" origin)))))

(defn error-response [id message]
  {"jsonrpc" "2.0"
   "id" id
   "error" {"code" -32603
            "message" message}})

(defn response-for
  "The answer to the client's `msg`, from what the clojure-atlas process answered, `res`."
  [msg res]
  (let [id (get msg "id")]
    (case res
      ::timeout (error-response id "clojure-atlas did not answer in time")
      ::gone (error-response id "clojure-atlas stopped before it answered; ask again")
      (assoc res "id" id))))

;; The clojure-atlas processes, one per repo

(defonce ^:private servers (atom {}))
(defonce ^:private next-id (atom 0))

(defn- deliver-responses!
  "Hands each answer the process writes to the call waiting for it, and tells the calls still
  waiting when the process ends."
  [out pending]
  (with-open [r (io/reader out)]
    (doseq [line (line-seq r)]
      (let [msg (try (json/parse-string line) (catch Exception _ nil))
            id (get msg "id")]
        (some-> (get @pending id) (deliver msg))
        (swap! pending dissoc id))))
  (let [[waiting _] (reset-vals! pending {})]
    (run! #(deliver % ::gone) (vals waiting))))

(defn- start-server! [repo]
  (let [graph (graph-file repo)
        proc (p/process ["clojure-atlas" "mcp" "--graph" graph "--repo" repo]
                        {:err :inherit})
        pending (atom {})]
    (future (deliver-responses! (:out proc) pending))
    {:repo repo
     :graph graph
     :stamp (stamp graph)
     :proc proc
     :writer (io/writer (:in proc))
     :pending pending}))

(defn- retire!
  "Closes the process's input, so it ends once it has answered what it was asked."
  [{:keys [writer proc]}]
  (try (.close writer) (catch Exception _ nil))
  (future (Thread/sleep call-timeout-ms)
          (p/destroy proc)))

(defn- server-for! [repo]
  (locking servers
    (let [current (get @servers repo)]
      (if (and current
               (p/alive? (:proc current))
               (not (stale? (:stamp current) (stamp (:graph current)) (System/currentTimeMillis))))
        current
        (do (some-> current retire!)
            (let [server (start-server! repo)]
              (swap! servers assoc repo server)
              server))))))

(defn- call! [{:keys [writer pending]} msg]
  (let [id (swap! next-id inc)
        answer (promise)]
    (swap! pending assoc id answer)
    (try
      (locking writer
        (.write writer (str (json/generate-string (assoc msg "id" id)) "\n"))
        (.flush writer))
      (deref answer call-timeout-ms ::timeout)
      (catch java.io.IOException _
        ::gone)
      (finally
        (swap! pending dissoc id)))))

(defn- answer
  "The answer to one message from a client, or nil for a notification, which gets none."
  [repo msg]
  (when (contains? msg "id")
    (if (stamp (graph-file repo))
      (response-for msg (call! (server-for! repo) msg))
      (error-response (get msg "id")
                      (str "No clojure-atlas graph at " (graph-file repo)
                           ". Build it with: bbg clojure-atlas build --repo " repo)))))

(defn status []
  {"service" "clojure-atlas"
   "graphs" (for [[repo {:keys [graph stamp proc]}] @servers]
              {"repo" repo
               "graph" graph
               "built" stamp
               "pid" (.pid (:proc proc))
               "alive" (p/alive? proc)})})

;; HTTP

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn- answer-post [repo body]
  (let [msg (json/parse-string body)
        answers (if (sequential? msg)
                  (seq (keep #(answer repo %) msg))
                  (answer repo msg))]
    (if answers
      (json-response 200 answers)
      {:status 202})))

(defn handler [{:keys [request-method uri query-string headers body]}]
  (let [repo (query-param query-string "repo")]
    (cond
      (and (= :get request-method) (= "/status" uri)) (json-response 200 (status))
      (not= "/mcp" uri) {:status 404}
      (foreign-origin? (get headers "origin")) {:status 403}
      (not= :post request-method) {:status 405 :headers {"Allow" "POST"}}
      (not (some-> repo fs/absolute?)) {:status 400
                                        :body "Name the repository with ?repo=<absolute path>"}
      :else (answer-post (str (fs/canonicalize repo)) (slurp body)))))

(defn- serving? [port]
  (try
    (= "clojure-atlas"
       (-> (http-client/get (str "http://127.0.0.1:" port "/status") {:timeout 2000})
           :body
           json/parse-string
           (get "service")))
    (catch Exception _ false)))

(defn- bind!
  "Starts serving on `port`, or returns nil when something already holds the port."
  [port]
  (try (http/run-server #'handler {:ip "127.0.0.1" :port port})
       (catch java.net.BindException _ nil)))

(defn serve!
  "Serves the graphs until the process is stopped. While another process serves on `port`, this
  one stands by, and takes the port over when that process stops, so the server stays up as long
  as any window runs its serve task."
  [{:keys [port] :or {port default-port}}]
  (let [url (str "http://127.0.0.1:" port)]
    (loop [standing-by? false]
      (if (bind! port)
        (do (println (str "clojure-atlas: serving on " url "/mcp?repo=<absolute repo path>"))
            @(promise))
        (do (when-not standing-by?
              (println (if (serving? port)
                         (str "clojure-atlas: standing by; another process serves on " url
                              ", and this one takes over when it stops")
                         (str "clojure-atlas: standing by; something else holds port " port))))
            (Thread/sleep 2000)
            (recur true))))))

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
    (do (println "Usage: bbg clojure-atlas build [--repo DIR]")
        (println "       bbg clojure-atlas serve [--port 7979]")
        (println "MCP clients connect to http://127.0.0.1:<port>/mcp?repo=<absolute repo path>"))))

(comment
  (def stop (http/run-server #'handler {:ip "127.0.0.1" :port 7980}))
  (status)
  (stop)
  :rcf)
