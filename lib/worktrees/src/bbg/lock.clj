(ns bbg.lock
  "Runs a command in a checkout, one run at a time across a project's checkouts. The runs take turns through an OS file lock in the state folder, which the OS releases when the run's process ends."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [bbg.worktree :as worktree]
            [bbg.worktree.config :as config]
            [clojure.string :as str]))

(def cli-spec
  {:coerce {:worktree :string}})

(def usage "Usage: lock <lock> [--worktree <name>] -- <command> [<arg>...]")

(defn- fail [message data]
  (throw (ex-info message (assoc data :babashka/exit 1))))

(defn parse-lock-args
  "Returns the lock's name, the worktree option and the command from the command-line `args`, split at `--`."
  [args]
  (let [[before [_ & command]] (split-with #(not= "--" %) args)
        {[lock-name] :args {:keys [worktree]} :opts} (cli/parse-args before cli-spec)]
    {:lock/name lock-name
     :lock/worktree worktree
     :lock/command (vec command)}))

(defn lock-problem
  "Returns why the parsed lock args can't run, or nil."
  [{:lock/keys [command worktree] lock-name :lock/name}]
  (cond
    (str/blank? lock-name) usage
    (not (config/valid-name? lock-name))
    (str "The lock name " lock-name " has characters other than letters, digits, dots, dashes and underscores, or starts with a dot.")
    (and worktree (not (config/valid-name? worktree)))
    (str "No worktree can be named " worktree ".")
    (empty? command) usage))

(defn try-lock
  "Returns an open channel holding the OS lock on the file at `path`, or nil when another holder has it. Closing the channel releases the lock."
  [path]
  (let [channel (java.nio.channels.FileChannel/open
                 (fs/path path)
                 (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE
                                                       java.nio.file.StandardOpenOption/WRITE]))]
    (if (try (.tryLock channel) (catch Exception _ nil))
      channel
      (do (.close channel) nil))))

(defn- take-lock!
  "Returns the channel holding the lock, after waiting while another run holds it. Records `checkout` as the holder."
  [lock-file holder-file lock-name checkout]
  (loop [told? false]
    (if-let [channel (try-lock lock-file)]
      (do (spit holder-file checkout)
          channel)
      (do (when-not told?
            (println "Waiting for the" lock-name "run in"
                     (if (fs/exists? holder-file) (slurp holder-file) "another checkout") "to finish..."))
          (Thread/sleep 1000)
          (recur true)))))

(defn run-locked!
  "Runs the parsed lock args' command in its checkout, holding the lock. Returns the command's exit code."
  [config cwd {:lock/keys [worktree command] lock-name :lock/name :as parsed}]
  (when-let [problem (lock-problem parsed)]
    (fail problem {}))
  (let [checkout (or worktree (config/checkout-name config (str (fs/canonicalize cwd))))
        dir (config/checkout-dir config checkout)]
    (when-not (fs/exists? dir)
      (fail (str "No checkout at " dir) {:worktree checkout}))
    (let [state-dir (worktree/state-dir! config)
          lock (take-lock! (str (fs/path state-dir (str lock-name ".lock")))
                           (str (fs/path state-dir (str lock-name ".lock.holder")))
                           lock-name
                           (or checkout "source"))]
      (try
        (:exit (p/shell {:dir dir :continue true :cmd command}))
        (finally
          (.close lock))))))

(defn exec!
  "Runs `bbg lock` with the command-line `args` from the working folder, and returns the command's exit code."
  [args]
  (let [cwd (System/getProperty "user.dir")]
    (run-locked! (config/config cwd) cwd (parse-lock-args args))))
