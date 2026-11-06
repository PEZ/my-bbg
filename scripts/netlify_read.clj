(ns netlify-read
  "Runs `netlify api` for read operations only: those the Netlify OpenAPI spec serves with GET."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]))

(defn- spec-path
  "The OpenAPI spec that ships with the `netlify` CLI on PATH."
  []
  (some-> (fs/which "netlify")
          fs/real-path
          fs/parent
          fs/parent
          (fs/path "node_modules" "@netlify" "open-api" "dist" "swagger.json")))

(defn read-operations
  "The ids of the operations that `spec` serves with GET."
  [spec]
  (into (sorted-set)
        (for [[_path methods] (get spec "paths")
              [method operation] methods
              :when (and (= "get" method) (map? operation) (get operation "operationId"))]
          (get operation "operationId"))))

(defn api-command
  "The `netlify api` command for `args`, or an error when the operation isn't a read."
  [read-ops [operation & more]]
  (if (contains? read-ops operation)
    {:netlify-read/command (into ["netlify" "api" operation] more)}
    {:netlify-read/error (str operation " isn't a read operation in the Netlify API. "
                              "`bbg netlify-read --list` shows the read operations. "
                              "Changes on Netlify need PEZ.")}))

(defn- fail! [message]
  (binding [*out* *err*]
    (println message))
  (System/exit 1))

(defn exec!
  "Lists the read operations for no args or `--list`, otherwise runs `netlify api` for a read."
  [args]
  (let [path (spec-path)]
    (when-not (some-> path fs/exists?)
      (fail! "Can't find the Netlify API spec. Is netlify-cli installed and on PATH?"))
    (let [read-ops (read-operations (json/parse-string (slurp (str path))))]
      (if (or (empty? args) (= "--list" (first args)))
        (run! println read-ops)
        (let [{:netlify-read/keys [command error]} (api-command read-ops args)]
          (when error
            (fail! error))
          (System/exit (:exit (apply p/shell {:continue true} command))))))))

(comment
  (def spec (json/parse-string (slurp (str (spec-path)))))
  (count (read-operations spec))
  (api-command (read-operations spec) ["listSites"])
  (api-command (read-operations spec) ["deleteSite"])
  :rcf)
