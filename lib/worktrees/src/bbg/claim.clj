(ns bbg.claim
  "Lets agents tell each other that they use a shared resource. A claim names the agent, its checkout and since when, in the state folder, and goes stale after some minutes."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [bbg.worktree :as worktree]
            [bbg.worktree.config :as config]
            [clojure.edn :as edn]))

(def cli-spec
  {:coerce {:worktree :string
            :minutes :long}})

(def usage
  "Usage: claim take <resource> <who> [--worktree <name>] [--minutes 30]\n       claim release <resource> <who>")

(def default-minutes 30)

(defn- fail [message data]
  (throw (ex-info message (assoc data :babashka/exit 1))))

(defn claim-state
  "Returns `:free` when there is no `claim`, `:stale` when it is older than `max-age-ms` at `now-ms`, else `:held`. A claim being written reads as `:unreadable`, and is held."
  [claim now-ms max-age-ms]
  (cond
    (nil? claim) :free
    (= :unreadable claim) :held
    (> (- now-ms (:claim/since claim)) max-age-ms) :stale
    :else :held))

(defn read-claim
  "Returns the claim in `file`, nil when there is none, or `:unreadable`."
  [file]
  (when (fs/exists? file)
    (try
      (or (edn/read-string (slurp file)) :unreadable)
      (catch Exception _ :unreadable))))

(defn- create! [file]
  (try (fs/create-file file) true
       (catch java.nio.file.FileAlreadyExistsException _ false)))

(defn- move-aside!
  "Moves the stale claim in `file` aside, so only one of two agents taking it over succeeds."
  [file now-ms]
  (let [aside (str file ".stale-" now-ms "-" (rand-int 1000000))]
    (try (fs/move file aside {:atomic-move true})
         (fs/delete-if-exists aside)
         (catch Exception _ nil))))

(defn- claim-for-age
  "Returns the claim in `file`, or for a claim that can't be read, its file's age, so a claim left half written also goes stale."
  [file]
  (let [claim (read-claim file)]
    (if (and (= :unreadable claim) (fs/exists? file))
      {:claim/since (.toMillis (fs/last-modified-time file))}
      claim)))

(defn claim!
  "Claims the resource in `file` for the agent `who` in `checkout` at `now-ms`. Returns `:claimed`, or `:held` when another agent holds it."
  [file who checkout now-ms max-age-ms]
  (loop [tries 3]
    (cond
      (create! file)
      (do (spit file (pr-str {:claim/who who
                              :claim/checkout checkout
                              :claim/since now-ms}))
          :claimed)

      (and (pos? tries) (= :stale (claim-state (claim-for-age file) now-ms max-age-ms)))
      (do (move-aside! file now-ms)
          (recur (dec tries)))

      :else :held)))

(defn release!
  "Releases the claim in `file` when the agent `who` holds it. Returns `:released`, `:not-yours` or `:free`."
  [file who]
  (let [claim (read-claim file)]
    (cond
      (nil? claim) :free
      (= who (:claim/who claim)) (do (fs/delete-if-exists file) :released)
      :else :not-yours)))

(defn describe
  "Returns a sentence saying who holds `claim` on `resource`."
  [resource claim]
  (if (map? claim)
    (str (:claim/who claim) " holds " resource " in " (:claim/checkout claim)
         ", since " (java.time.Instant/ofEpochMilli (:claim/since claim)) ".")
    (str "Another agent is claiming " resource " right now.")))

(defn- claim-file!
  "Returns the file of the claim on `resource`, in the state folder, created when missing."
  [config resource]
  (cond
    (nil? resource) (fail usage {})
    (not (config/valid-name? resource))
    (fail (str "The resource name " resource " has characters other than letters, digits, dots, dashes and underscores, or starts with a dot.") {}))
  (str (fs/path (worktree/state-dir! config) (str resource ".claim"))))

(defn take!
  "Claims `resource` for `who` in the named worktree, or the checkout holding `cwd`. Returns the exit code: 0 when claimed, 1 when another agent holds it."
  [config cwd {:keys [resource who worktree minutes]}]
  (when-not who (fail usage {}))
  (when (and worktree (not (config/valid-name? worktree)))
    (fail (str "No worktree can be named " worktree ".") {}))
  (let [file (claim-file! config resource)
        checkout (or worktree (config/checkout-name config (str (fs/canonicalize cwd))) "source")]
    (case (claim! file who checkout (System/currentTimeMillis) (* 60 1000 (or minutes default-minutes)))
      :claimed (do (println "You hold" resource (str ", " who ".") "Release it with: claim release" resource who) 0)
      :held (do (println (describe resource (read-claim file)) "Wait, and claim it again.") 1))))

(defn release-claim!
  "Releases `who`'s claim on `resource`. Returns the exit code: 0 when released or free, 1 when another agent holds it."
  [config {:keys [resource who]}]
  (when-not who (fail usage {}))
  (let [file (claim-file! config resource)]
    (case (release! file who)
      :released (do (println "Released" (str resource ".")) 0)
      :free (do (println "Nobody holds" (str resource ".")) 0)
      :not-yours (do (println (describe resource (read-claim file)) "Not released.") 1))))

(defn exec!
  "Runs `bbg claim` with the command-line `args` from the working folder, and returns the exit code."
  [[subcommand & args]]
  (let [cwd (System/getProperty "user.dir")
        {[resource who] :args {:keys [worktree minutes]} :opts} (cli/parse-args args cli-spec)
        opts {:resource resource :who who :worktree worktree :minutes minutes}]
    (case subcommand
      "take" (take! (config/config cwd) cwd opts)
      "release" (release-claim! (config/config cwd) opts)
      (fail usage {}))))
