(ns bbg.worktree.config
  "Finds and reads a project's `worktrees.edn`, and maps checkouts to folders."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def file-name "worktrees.edn")

(def defaults
  {:worktree/source "."
   :worktree/dir "worktrees"
   :worktree/remote "origin"
   :worktree/prepare []})

(def known-keys
  #{:worktree/source :worktree/dir :worktree/remote :worktree/ports
    :worktree/control :worktree/prepare :worktree/claude-code})

(defn- fail [message data]
  (throw (ex-info message (assoc data :babashka/exit 1))))

(defn- git-lines [dir & args]
  (let [{:keys [exit out]} (apply p/sh {:dir (str dir)} "git" args)]
    (when (zero? exit)
      (str/split-lines (str/trim out)))))

(defn- type-problems [raw]
  (keep (fn [[k pred what]]
          (when (and (contains? raw k) (not (pred (get raw k))))
            (str k " should be " what ", not " (pr-str (get raw k)) ".")))
        [[:worktree/source string? "a folder"]
         [:worktree/dir string? "a folder"]
         [:worktree/remote string? "a remote's name"]
         [:worktree/control map? "a map"]
         [:worktree/claude-code map? "a map"]
         [:worktree/prepare #(and (vector? %) (every? vector? %)) "a vector of steps, each a vector"]]))

(defn- port-problems [ports control?]
  (if-not (and (map? ports) (seq ports))
    ["`:worktree/ports` needs at least one port, as in `{:app {:first 8092}}`."]
    (for [[port-name {first-port :first control-port :control}] ports
          problem [(when-not (int? first-port)
                     (str "The port " port-name " needs a `:first` number."))
                   (when (and control? (not (int? control-port)))
                     (str "The port " port-name " needs a `:control` number, since there is a control."))]
          :when problem]
      problem)))

(defn- step-problem [step]
  (let [[kind & args] step]
    (case kind
      :run (when-not (and (= 1 (count args)) (vector? (first args)) (seq (first args)))
             (str "The step " (pr-str step) " needs a command vector, as in `[:run [\"npm\" \"run\" \"build\"]]`."))
      :share (let [[dir {:keys [same install]}] args]
               (when-not (and (string? dir) (string? same) (vector? install) (seq install))
                 (str "The step " (pr-str step) " needs a folder, a `:same` file and an `:install` command, as in `[:share \"node_modules\" {:same \"package-lock.json\" :install [\"npm\" \"ci\"]}]`.")))
      (str "The step " (pr-str step) " is neither `:run` nor `:share`."))))

(defn- claude-code-problems [claude-code]
  (when (and claude-code (not (string? (:dev-dir claude-code))))
    ["`:worktree/claude-code` needs a `:dev-dir` folder, as in `{:dev-dir \".\"}`."]))

(defn problems
  "Returns what is wrong with the config map `raw`, as sentences."
  [raw]
  (if-not (map? raw)
    ["The config is not a map."]
    (let [unknown (map #(str "Unknown key " % ".") (remove known-keys (keys raw)))
          typed (type-problems raw)]
      (vec (concat unknown
                   typed
                   (when (empty? typed)
                     (concat (port-problems (:worktree/ports raw) (contains? raw :worktree/control))
                             (keep step-problem (:worktree/prepare raw))
                             (claude-code-problems (:worktree/claude-code raw)))))))))

(defn resolve-config
  "Returns `raw`, the config in the folder `dir`, with defaults filled in and its paths absolute."
  [dir raw]
  (let [config (merge defaults raw)
        abs #(str (fs/normalize (fs/path dir %)))
        worktrees-dir (abs (:worktree/dir config))]
    (cond-> (assoc config
                   :worktree/config-dir (str dir)
                   :worktree/source (abs (:worktree/source config))
                   :worktree/dir worktrees-dir
                   :worktree/state-dir (str (fs/path worktrees-dir ".state")))
      (:worktree/claude-code config) (update-in [:worktree/claude-code :dev-dir] abs))))

(defn- nearest-config-dir [dir]
  (some #(when (fs/exists? (fs/path % file-name)) (str %))
        (take-while some? (iterate fs/parent (fs/canonicalize dir)))))

(defn- main-checkout-dir
  "Returns the folder in the main checkout that matches `dir` in a linked worktree, or nil when `dir` isn't in one."
  [dir]
  (when-let [[git-dir common-dir top] (git-lines dir "rev-parse" "--path-format=absolute"
                                                 "--git-dir" "--git-common-dir" "--show-toplevel")]
    (when (not= git-dir common-dir)
      (str (fs/path (fs/parent common-dir) (fs/relativize top dir))))))

(defn config-dir
  "Returns the folder of the config that applies at `dir`, or nil when there is none."
  [dir]
  (when-let [found (nearest-config-dir dir)]
    (if-let [main (main-checkout-dir found)]
      (if (fs/exists? (fs/path main file-name))
        main
        (fail (str "The worktree " found " has a " file-name ", but its main checkout " main
                   " has none, and the main checkout's config is the one that applies.")
              {:found found :main main}))
      found)))

(defn read-config
  "Returns the config in the folder `dir`, checked and resolved."
  [dir]
  (let [file (str (fs/path dir file-name))
        raw (try (edn/read-string (slurp file))
                 (catch Exception e
                   (fail (str "Can't read " file ": " (ex-message e)) {:file file})))]
    (if-let [found (seq (problems raw))]
      (fail (str file " has problems:\n" (str/join "\n" (map #(str "- " %) found)))
            {:file file :problems found})
      (resolve-config dir raw))))

(defn config
  "Returns the config that applies in the folder `cwd`."
  [cwd]
  (if-let [dir (config-dir cwd)]
    (read-config dir)
    (fail (str "No " file-name " in " (fs/canonicalize cwd) " or any folder above it.")
          {:cwd (str cwd)})))

(defn checkout-name
  "Returns the name of the worktree whose folder holds `cwd`, or nil for anywhere else."
  [{worktrees-dir :worktree/dir} cwd]
  (let [prefix (str worktrees-dir "/")
        path (str cwd "/")]
    (when (str/starts-with? path prefix)
      (let [worktree-name (first (str/split (subs path (count prefix)) #"/"))]
        (when-not (or (str/blank? worktree-name) (str/starts-with? worktree-name "."))
          worktree-name)))))

(defn checkout-dir
  "Returns the folder of the worktree `worktree-name`, or the source checkout's when it is nil."
  [{:worktree/keys [source] worktrees-dir :worktree/dir} worktree-name]
  (if worktree-name
    (str (fs/path worktrees-dir worktree-name))
    source))
