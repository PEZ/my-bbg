(ns bbg.claim-test
  (:require
   [babashka.fs :as fs]
   [bbg.claim :as claim]
   [clojure.test :refer [deftest is]]))

(def max-age-ms (* 30 60 1000))

(deftest claim-states
  (is (= :free (claim/claim-state nil 1000 max-age-ms))
      "No claim: free")
  (is (= :held (claim/claim-state {:claim/since 1000} 2000 max-age-ms))
      "A recent claim is held")
  (is (= :stale (claim/claim-state {:claim/since 0} (inc max-age-ms) max-age-ms))
      "A claim older than its minutes was left behind, and can be taken over")
  (is (= :held (claim/claim-state :unreadable 1000 max-age-ms))
      "A claim being written is held"))

(deftest claiming
  (let [file (str (fs/path (fs/create-temp-dir) "shared.claim"))]
    (is (= :claimed (claim/claim! file "agent-a" "source" 1000 max-age-ms))
        "A free claim is taken")
    (is (= :held (claim/claim! file "agent-b" "x" 2000 max-age-ms))
        "Another agent finds it held")
    (is (= :not-yours (claim/release! file "agent-b"))
        "Only the holder releases it")
    (is (= :released (claim/release! file "agent-a"))
        "The holder releases it")
    (is (= :claimed (claim/claim! file "agent-b" "x" 3000 max-age-ms))
        "The next agent takes it")
    (is (= {:claim/who "agent-b" :claim/checkout "x" :claim/since 3000} (claim/read-claim file))
        "The claim names who, where and since when")
    (is (= :claimed (claim/claim! file "agent-c" "source" (+ 3000 max-age-ms 1) max-age-ms))
        "A stale claim is taken over")))
