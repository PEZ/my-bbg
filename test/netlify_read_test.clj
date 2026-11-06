(ns netlify-read-test
  (:require [clojure.test :refer [deftest is]]
            [netlify-read :as sut]))

(def ^:private spec
  {"paths" {"/sites" {"get" {"operationId" "listSites"}
                      "post" {"operationId" "createSite"}
                      "parameters" [{"name" "x"}]}
            "/sites/{site_id}" {"get" {"operationId" "getSite"}
                                "delete" {"operationId" "deleteSite"}}
            "/tickets/{ticket_id}" {"get" {"operationId" "showTicket"}}}})

(deftest read-operations
  (is (= #{"getSite" "listSites" "showTicket"} (sut/read-operations spec))
      "Reads are the GET operations, whatever their names"))

(deftest api-command
  (let [read-ops (sut/read-operations spec)]
    (is (= {:netlify-read/command ["netlify" "api" "getSite" "--data" "{\"site_id\":\"x\"}"]}
           (sut/api-command read-ops ["getSite" "--data" "{\"site_id\":\"x\"}"]))
        "A read runs netlify api with the given args")
    (is (contains? (sut/api-command read-ops ["deleteSite"]) :netlify-read/error)
        "A write is refused")
    (is (contains? (sut/api-command read-ops ["noSuchOperation"]) :netlify-read/error)
        "An unknown operation is refused")))
