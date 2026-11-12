(ns clojure-atlas-test
  (:require [clojure-atlas :as atlas]
            [clojure.test :refer [deftest is]]))

(deftest repo-query-param
  (is (= "/Users/pez/x" (atlas/query-param "repo=%2FUsers%2Fpez%2Fx&y=1" "repo"))
      "The repo is read URL-decoded from the query")
  (is (nil? (atlas/query-param "y=1" "repo"))
      "No repo param gives nil")
  (is (nil? (atlas/query-param nil "repo"))
      "No query gives nil"))

(deftest graph-staleness
  (is (false? (atlas/stale? 1000 1000 999999))
      "The graph the server started on is not stale")
  (is (false? (atlas/stale? 1000 5000 5500))
      "A graph written moments ago may still be being written")
  (is (true? (atlas/stale? 1000 5000 9000))
      "A rebuilt graph left alone long enough is picked up")
  (is (false? (atlas/stale? 1000 nil 9000))
      "A graph that is gone keeps the running server"))

(deftest origin-check
  (is (false? (atlas/foreign-origin? nil))
      "Clients that send no origin, like agents, are let in")
  (is (false? (atlas/foreign-origin? "http://localhost:3000"))
      "Local pages are let in")
  (is (true? (atlas/foreign-origin? "https://evil.example"))
      "Other sites are refused"))

(deftest client-ids-come-back
  (is (= {"jsonrpc" "2.0" "id" "a" "result" {}}
         (atlas/response-for {"id" "a"} {"jsonrpc" "2.0" "id" 7 "result" {}}))
      "The answer carries the client's id, not the bridge's")
  (is (= 3 (get (atlas/response-for {"id" 3} ::atlas/gone) "id"))
      "An error for a process that stopped carries the client's id")
  (is (contains? (atlas/response-for {"id" 3} ::atlas/timeout) "error")
      "A call that timed out answers with an error"))
