(ns watch
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [pod.babashka.fswatcher :as fw])
  (:import [java.util.concurrent LinkedBlockingQueue]))

(defn- run-command! [label cmd]
  (println (str "\n[RUN] " label))
  (flush)
  (let [result (p/shell {:continue true} cmd)]
    (if (zero? (:exit result))
      (println (str "[PASS] " label))
      (println (str "[FAIL] " label " (exit " (:exit result) ")")))
    (flush)))

(defn- matches-extensions? [path extensions]
  (some #(str/ends-with? (str path) %) extensions))

(defn watch!
  "Watch dirs for file changes and re-run cmd with debouncing.
   opts: :extensions - seq of file extensions to match (e.g. [\".clj\" \".toml\"])
         :debounce-ms - debounce delay in ms (default 300)"
  [label cmd dirs opts]
  (let [extensions (:extensions opts [".clj"])
        debounce-ms (:debounce-ms opts 300)
        trigger (LinkedBlockingQueue.)]
    ;; Worker thread: takes from queue, debounces, runs command
    (future
      (loop []
        (.take trigger)
        (Thread/sleep debounce-ms)
        (.clear trigger)
        (run-command! label cmd)
        (println "Watching for changes...")
        (flush)
        (recur)))
    ;; Set up file watchers — filter only on extension, not event type,
    ;; because VS Code's atomic save (write-to-temp + rename) may not
    ;; produce :write events
    (doseq [dir dirs]
      (fw/watch dir
               (fn [event]
                 (when (matches-extensions? (:path event) extensions)
                   (.put trigger :change)))
               {:recursive true}))
    ;; Trigger initial run
    (.put trigger :init)
    ;; Block forever
    (deref (promise))))
