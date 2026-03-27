(ns bb-nrepl
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.nrepl.server :as srv]))

(def cli-spec
  {:spec {:port {:coerce :long :alias :p
                 :desc "Port number (0 for random, default: 0)"}
          :help {:coerce :boolean :alias :h
                 :desc "Show this help"}}})

(defn- help-text []
  (str "Usage: bbg bb-nrepl [options]\n\n"
       "Start a Babashka nREPL server and write port to ./bb/.nrepl-port\n\n"
       "Options:\n"
       (cli/format-opts cli-spec)))

(defn start! [opts]
  (if (:help opts)
    (println (help-text))
    (let [port (or (:port opts) 0)
          {:keys [socket]} (srv/start-server! {:host "localhost" :port port})
          actual-port (.getLocalPort socket)
          port-file (str (fs/file "bb" ".nrepl-port"))]
      (fs/create-dirs "bb")
      (spit port-file (str actual-port))
      (println (str "bb nREPL server started on port " actual-port))
      (println (str "Port written to " port-file))
      (-> (Runtime/getRuntime)
          (.addShutdownHook
           (Thread. (fn [] (fs/delete-if-exists port-file)))))
      (deref (promise)))))
