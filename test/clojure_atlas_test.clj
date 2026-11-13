(ns clojure-atlas-test
  (:require [clojure-atlas :as atlas]
            [clojure.test :refer [deftest is]]))

(deftest served-repo-selection
  (is (= ["/a" "/b" "/c"]
         (atlas/served-repos {"/c" 2 "/a" 3 "/gone" 5 "/b" 1} #(not= "/gone" %)))
      "Repos that no longer exist are left out, and the rest come sorted")
  (is (= (map #(str "/r" %) (range 8 40))
         (sort-by #(parse-long (subs % 2))
                  (atlas/served-repos (into {} (map (fn [i] [(str "/r" i) i])) (range 40))
                                      (constantly true))))
      "With more repos than clojure-atlas takes, the most recently registered are served")
  (is (= [] (atlas/served-repos {} (constantly true)))
      "An empty registry serves nothing"))

(deftest serve-command-line
  (is (= ["clojure-atlas" "serve" "--port" "7979" "--repo" "/a" "--repo" "/b"]
         (atlas/serve-command ["clojure-atlas"] ["/a" "/b"] 7979))
      "Each repo gets its own --repo"))
