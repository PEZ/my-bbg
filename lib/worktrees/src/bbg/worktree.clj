(ns bbg.worktree
  "Prepares, lists, stops and removes a project's worktrees, as its `worktrees.edn` says."
  (:require [bbg.worktree.config :as config]
            [clojure.pprint :as pprint]))

(def cli-spec
  {:coerce {}})

(def usage
  "Usage: worktree config")

(defn- cwd []
  (System/getProperty "user.dir"))

(defn print-config
  "Prints the config that applies in the folder `dir`, with its defaults filled in."
  [dir]
  (pprint/pprint (into (sorted-map) (config/config dir))))

(defn exec!
  "Runs the worktree subcommand in `args`, from the working folder."
  [[subcommand]]
  (case subcommand
    "config" (print-config (cwd))
    (throw (ex-info usage {:babashka/exit 1}))))
