(ns bbg.lock-test
  (:require
   [babashka.fs :as fs]
   [bbg.lock :as lock]
   [clojure.test :refer [deftest is]]))

(deftest the-lock
  (let [path (str (fs/create-temp-file {:prefix "lock-test"}))
        first-holder (lock/try-lock path)]
    (is (some? first-holder)
        "A free lock is taken")
    (is (nil? (lock/try-lock path))
        "A held lock is not taken again")
    (.close first-holder)
    (let [next-holder (lock/try-lock path)]
      (is (some? next-holder)
          "A released lock is taken by the next run")
      (.close next-holder))
    (fs/delete-if-exists path)))

(deftest args
  (is (= {:lock/name "e2e" :lock/worktree nil :lock/command ["bb" "e2e" "--headed"]}
         (lock/parse-lock-args ["e2e" "--" "bb" "e2e" "--headed"]))
      "The lock, then the command after --, with its own options")
  (is (= {:lock/name "e2e" :lock/worktree "x" :lock/command ["bb" "e2e" "--" "-g" "drag"]}
         (lock/parse-lock-args ["e2e" "--worktree" "x" "--" "bb" "e2e" "--" "-g" "drag"]))
      "A worktree, and a command with a -- of its own")
  (is (some? (lock/lock-problem (lock/parse-lock-args ["e2e"])))
      "No command")
  (is (some? (lock/lock-problem (lock/parse-lock-args ["../x" "--" "true"])))
      "A lock name that would leave the state folder"))
