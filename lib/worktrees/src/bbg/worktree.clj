(ns bbg.worktree
  "Prepares, lists, stops and removes a project's worktrees, as its `worktrees.edn` says."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [bbg.worktree.config :as config]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def control-name "control")

(def cli-spec
  {:coerce {:new-branch :boolean
            :detach :boolean
            :worktree :string}})

(def usage
  (str/join "\n" ["Usage: worktree <subcommand>"
                  "  prepare <name> <ref> [--new-branch] [--detach]"
                  "  check <name> <ref> [--new-branch] [--detach]"
                  "  stop <name>"
                  "  remove <name>"
                  "  items worktree|control|running"
                  "  port [<port-name>] [--worktree <name>]"
                  "  name <branch>"
                  "  config"]))

(defn- fail [message data]
  (throw (ex-info message (assoc data :babashka/exit 1))))

;; Names and ports

(defn name-of-branch
  "Returns the worktree name for `branch`: its last segment."
  [branch]
  (last (str/split branch #"/")))

(def valid-name? config/valid-name?)

(defn name-problem
  "Returns why the worktree `name` can't take `ref`, or nil. Only the control is detached, and only in a project with one. Any other worktree is named after its branch's last segment, and a name belongs to the branch whose worktree has it."
  [{worktree-name :name git-ref :ref :keys [detach? registry control?]}]
  (let [{taken-by :worktree/ref} (get registry worktree-name)
        control-named? (= control-name worktree-name)]
    (cond
      (or (str/blank? worktree-name) (str/blank? git-ref))
      "Usage: worktree prepare <name> <ref> [--new-branch] [--detach]"

      (and control-named? (not detach?))
      (str "The name control belongs to the control. Branch " git-ref " can't get a worktree under that name.")

      (and control-named? (not control?))
      "This project has no control. Add :worktree/control to its worktrees.edn to have one."

      control-named? nil

      (not (valid-name? worktree-name))
      (str "The worktree name " worktree-name " has characters other than letters, digits, dots, dashes and underscores, or starts with a dot.")

      detach?
      (str "Only the control is detached. The worktree " worktree-name " needs a branch.")

      (not= worktree-name (name-of-branch git-ref))
      (str "The worktree for " git-ref " is named " (name-of-branch git-ref) ", not " worktree-name ".")

      (and taken-by (not= git-ref taken-by))
      (str "The worktree " worktree-name " is on " taken-by ". Remove it, or pick a branch with another last segment."))))

(defn branch-worktree-name
  "Returns `{:worktree/name ...}` for the worktree a branch would get, or `{:worktree/problem ...}` when no worktree can have that name."
  [branch control?]
  (let [worktree-name (when-not (str/blank? branch) (name-of-branch branch))]
    (if-let [problem (if worktree-name
                       (name-problem {:name worktree-name :ref branch :registry {} :control? control?})
                       "Usage: worktree name <branch>")]
      {:worktree/problem problem}
      {:worktree/name worktree-name})))

(defn- taken-ports
  "Returns every port number the registry's entries other than `worktree-name` hold, and every configured :control and :main."
  [ports registry worktree-name]
  (into (set (mapcat (comp vals :worktree/ports) (vals (dissoc registry worktree-name))))
        (keep identity (mapcat (juxt :control :main) (vals ports)))))

(defn ports-for
  "Returns the ports of the worktree `name`, by port name: the control's numbers for the control, its registry numbers on a restart, else the first number from each port's :first that is taken by nothing."
  [{worktree-name :name :keys [registry ports in-use?]}]
  (let [own (get-in registry [worktree-name :worktree/ports])]
    (loop [[[port-name {first-port :first control-port :control}] & more] (seq ports)
           taken (taken-ports ports registry worktree-name)
           result {}]
      (if-not port-name
        result
        (let [port (or (when (= control-name worktree-name) control-port)
                       (get own port-name)
                       (first (remove #(or (taken %) (in-use? %)) (iterate inc first-port))))]
          (recur more (conj taken port) (assoc result port-name port)))))))

(defn port-name-for
  "Returns the port name `asked`, or the only one in `ports` when none is asked, or why neither works."
  [ports asked]
  (let [port-names (keys ports)]
    (cond
      (and asked (contains? ports (keyword asked))) {:port/name (keyword asked)}
      asked {:port/problem (str "No port named " asked ". The ports are " (str/join ", " (map name port-names)) ".")}
      (= 1 (count port-names)) {:port/name (first port-names)}
      :else {:port/problem (str "Name one of the ports: " (str/join ", " (map name port-names)) ".")})))

;; The registry and git

(defn drop-missing
  "Returns `registry` without the entries whose name isn't in `existing`, the worktree folders on disk."
  [registry existing]
  (into {} (filter (comp existing key)) registry))

(defn checked-out-branches
  "Returns each branch checked out in a worktree of the source, mapped to its folder, from `git worktree list --porcelain` output."
  [porcelain]
  (into {}
        (keep (fn [block]
                (let [lines (str/split-lines block)
                      path (some #(when (str/starts-with? % "worktree ") (subs % 9)) lines)
                      branch (some #(when (str/starts-with? % "branch refs/heads/") (subs % 18)) lines)]
                  (when branch [branch path]))))
        (str/split porcelain #"\n\n")))

(defn worktree-paths
  "Returns the folders of all worktrees of the source, from `git worktree list --porcelain` output."
  [porcelain]
  (set (keep #(when (str/starts-with? % "worktree ") (subs % 9))
             (str/split-lines porcelain))))

(defn worktree-items
  "Returns the worktree picker's items for `branches`, in their order: each branch's worktree name, whether its worktree exists, and whether it runs, by the worktree names in `running`. Branches checked out in the source, or in a worktree outside `worktrees-path`, are left out."
  [{:keys [branches checked-out worktrees-path running]}]
  (keep (fn [branch]
          (let [path (get checked-out branch)
                ours? (and path (str/starts-with? path (str worktrees-path "/")))
                item-name (name-of-branch branch)]
            (when (or (nil? path) ours?)
              {:worktree/ref branch
               :worktree/name item-name
               :worktree/exists? (boolean ours?)
               :worktree/running? (boolean (and ours? (contains? running item-name)))})))
        branches))

(defn control-refs
  "Returns the refs for the control picker: `default-ref` first, then `refs` in their order, without `<remote>/HEAD` and the bare remote name."
  [default-ref remote refs]
  (cons default-ref (remove #{(str remote "/HEAD") remote default-ref} refs)))

(defn worktree-args
  "Returns the git args that put `ref` in the worktree at `path`: a new worktree of `source` when `exists?` is false, else a checkout in the existing one. `detach?` checks a ref out detached, as for the control, and `new-branch?` creates the branch `ref` at the commit `base` in a new worktree. An existing worktree already has its branch."
  [{git-ref :ref :keys [source exists? path detach? new-branch? base]}]
  (cond
    (and exists? detach?) ["git" "-C" path "checkout" "--detach" git-ref]
    exists? ["git" "-C" path "checkout" git-ref]
    detach? ["git" "-C" source "worktree" "add" "--detach" path git-ref]
    new-branch? ["git" "-C" source "worktree" "add" "-b" git-ref path base]
    :else ["git" "-C" source "worktree" "add" path git-ref]))

(defn removal-args
  "Returns the git commands that remove the worktree at `path` from `source`, and then delete its `branch` when it has one. The worktree removal refuses when the worktree has changes git would lose."
  [{:keys [source path branch]}]
  (cond-> [["git" "-C" source "worktree" "remove" path]]
    branch (conj ["git" "-C" source "branch" "-D" branch])))

;; Preparing a checkout

(defn share-steps
  "Returns what gives a worktree a folder it shares with the source: `:keep` a link to the source's while the two `same` files are equal, `:link` to the source's when there is none, else `:install`, after `:unlink` of a link. A folder of the worktree's own is replaced by the install."
  [{:keys [same? state]}]
  (cond
    (and same? (= :link state)) [:keep]
    (and same? (= :missing state)) [:link]
    (= :link state) [:unlink :install]
    :else [:install]))

(defn with-development-dir
  "Returns the Claude Code `settings` with the folder `dev-dir` among the added directories."
  [settings dev-dir]
  (update-in settings ["permissions" "additionalDirectories"]
             (fn [dirs] (vec (distinct (conj (vec dirs) dev-dir))))))

(defn exclude-lines
  "Returns the lines the source's shared git exclude file needs for `config`: each shared folder, the Claude Code skills link and settings file, and the worktrees folder when it is inside `source-top`, the source's repo folder."
  [{:worktree/keys [prepare claude-code] worktrees-dir :worktree/dir} source-top]
  (let [inside (str source-top "/")]
    (vec (concat (for [[kind dir] prepare :when (= :share kind)] (str "/" dir))
                 (when claude-code ["/.claude/skills" "/.claude/settings.local.json"])
                 (when (str/starts-with? (str worktrees-dir "/") inside)
                   [(str "/" (subs worktrees-dir (count inside)) "/")])))))

(defn missing-exclude-lines
  "Returns the `lines` that the git exclude file text `existing` lacks."
  [existing lines]
  (let [present (set (some-> existing str/split-lines))]
    (vec (remove present lines))))

(defn worktree-process?
  "True when the process's working folder `cwd` is the worktree folder `path`."
  [cwd path]
  (= (str cwd) (str path)))

(defn args->opts
  "Returns the prepare options from the command-line `args`: the name, the ref, and the flags `--new-branch` and `--detach`."
  [args]
  (let [{[worktree-name git-ref] :args {:keys [new-branch detach]} :opts} (cli/parse-args args cli-spec)]
    {:name worktree-name
     :ref git-ref
     :detach? (boolean detach)
     :new-branch? (boolean new-branch)}))

;; The edge: processes, files and git

(defn- sh-out
  "Returns the trimmed stdout of the command `args`, or nil when it fails."
  [& args]
  (let [{:keys [exit out]} (apply p/sh args)]
    (when (zero? exit)
      (str/trim out))))

(defn- process-cwd
  "Returns the working folder of process `pid`, or nil."
  [pid]
  (some->> (sh-out "lsof" "-a" "-p" (str pid) "-d" "cwd" "-Fn")
           str/split-lines
           (some #(when (str/starts-with? % "n") (subs % 1)))))

(defn- listening-pids [port]
  (some->> (sh-out "lsof" "-t" (str "-iTCP:" port) "-sTCP:LISTEN")
           str/split-lines))

(defn- port-listening? [port]
  (boolean (seq (listening-pids port))))

(defn- worktree-path [config worktree-name]
  (config/checkout-dir config worktree-name))

(defn- read-registry
  "Returns the registry, without entries whose worktree folder is gone."
  [{:worktree/keys [state-dir] worktrees-dir :worktree/dir}]
  (let [file (fs/path state-dir "registry.edn")]
    (drop-missing (if (fs/exists? file)
                    (edn/read-string (slurp (str file)))
                    {})
                  (set (map (comp str fs/file-name)
                            (when (fs/exists? worktrees-dir)
                              (filter fs/directory? (fs/list-dir worktrees-dir))))))))


(defn- worktree-ports
  "Returns the ports to look for the worktree's processes on: its `.nrepl-port`'s, and its `registry` ports."
  [config registry worktree-name]
  (let [port-file (fs/path (worktree-path config worktree-name) ".nrepl-port")]
    (distinct (concat (when (fs/exists? port-file) [(str/trim (slurp (str port-file)))])
                      (map str (vals (get-in registry [worktree-name :worktree/ports])))))))

(defn- worktree-pids
  "Returns the pids of the processes listening on the worktree's ports whose working folder is the worktree."
  ([config worktree-name]
   (worktree-pids config (read-registry config) worktree-name))
  ([config registry worktree-name]
   (let [path (fs/canonicalize (worktree-path config worktree-name))]
     (->> (worktree-ports config registry worktree-name)
          (mapcat listening-pids)
          distinct
          (filter #(worktree-process? (process-cwd %) path))
          vec))))

(defn- alive? [pid]
  (zero? (:exit (p/sh "kill" "-0" pid))))

(defn- worktree-list [{:worktree/keys [source]}]
  (or (sh-out "git" "-C" source "worktree" "list" "--porcelain") ""))

(defn- check-existing-worktree
  "Throws unless `worktree-name` is a valid name whose folder is a git worktree of the source."
  [config worktree-name]
  (let [path (worktree-path config worktree-name)]
    (when-not (valid-name? worktree-name)
      (fail (str "No worktree can be named " worktree-name ".") {:name worktree-name}))
    (when-not (contains? (worktree-paths (worktree-list config)) (str (fs/canonicalize path)))
      (fail (str "There is no worktree " worktree-name " at " path ".") {:name worktree-name}))
    path))

(defn- stop-pids! [worktree-name pids]
  (println "Stopping the processes of worktree" worktree-name "- pids" (str/join " " pids))
  (doseq [pid pids] (p/sh "kill" pid))
  (loop [tries 60]
    (cond
      (not-any? alive? pids) (do (println "The processes of worktree" worktree-name "have stopped.") pids)
      (zero? tries) (fail (str "The processes of worktree " worktree-name " did not stop within 30 seconds") {:pids pids})
      :else (do (Thread/sleep 500) (recur (dec tries))))))

(defn stop!
  "Stops the processes of the worktree `name` that listen on its ports, when any run, and waits for them to end. Returns their pids, or nil when none ran."
  [config worktree-name]
  (when (str/blank? worktree-name)
    (fail "Usage: worktree stop <name>" {}))
  (when-not (valid-name? worktree-name)
    (fail (str "No worktree can be named " worktree-name ".") {:name worktree-name}))
  (when (fs/exists? (worktree-path config worktree-name))
    (check-existing-worktree config worktree-name)
    (when-let [pids (not-empty (worktree-pids config worktree-name))]
      (stop-pids! worktree-name pids))))

(defn- link-state [path]
  (cond
    (fs/sym-link? path) :link
    (fs/exists? path) :dir
    :else :missing))

(defn- share! [{:worktree/keys [source]} path [_ dir {:keys [same install]}]]
  (let [target (fs/path path dir)
        same-text #(let [f (fs/path % same)] (when (fs/exists? f) (slurp (str f))))]
    (doseq [step (share-steps {:same? (and (fs/directory? (fs/path source dir))
                                           (= (same-text source) (same-text path)))
                               :state (link-state target)})]
      (case step
        :keep nil
        :link (fs/create-sym-link target (fs/canonicalize (fs/path source dir)))
        :unlink (fs/delete target)
        :install (p/shell {:dir (str path) :cmd install}))
      (println (str dir ":") (name step)))))

(defn- run-steps! [config path]
  (doseq [[kind & args :as step] (:worktree/prepare config)]
    (case kind
      :share (share! config path step)
      :run (p/shell {:dir (str path) :cmd (first args)}))))

(defn- give-claude-settings!
  "Links the worktree's `.claude/skills` to the dev folder's, and adds the dev folder to its `.claude/settings.local.json`."
  [{{:keys [dev-dir]} :worktree/claude-code} path]
  (let [file (fs/path path ".claude" "settings.local.json")
        skills (fs/path path ".claude" "skills")
        settings (when (fs/exists? file) (json/parse-string (slurp (str file))))]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (json/generate-string (with-development-dir settings dev-dir) {:pretty true}))
    (when-not (or (fs/exists? skills) (fs/sym-link? skills))
      (fs/create-sym-link skills (fs/path dev-dir ".claude" "skills")))))

(defn- exclude! [{:worktree/keys [source] :as config}]
  (let [file (fs/path (sh-out "git" "-C" source "rev-parse" "--path-format=absolute" "--git-common-dir") "info" "exclude")
        existing (when (fs/exists? file) (slurp (str file)))
        missing (missing-exclude-lines existing (exclude-lines config (sh-out "git" "-C" source "rev-parse" "--show-toplevel")))]
    (when (seq missing)
      (fs/create-dirs (fs/parent file))
      (spit (str file) (str (when (and existing (not (str/ends-with? existing "\n"))) "\n")
                            (str/join "\n" missing) "\n")
            :append true))))

(defn state-dir!
  "Returns the state folder, created, with the exclude lines `config` needs in place."
  [{:worktree/keys [state-dir] :as config}]
  (fs/create-dirs state-dir)
  (exclude! config)
  state-dir)

(defn- write-registry! [config registry]
  (spit (str (fs/path (state-dir! config) "registry.edn"))
        (with-out-str (pprint/pprint (into (sorted-map) registry)))))

(defn- register!
  "Records the worktree `name` at `ref` in the registry, with its ports, and returns the ports. Reads the registry just before writing it, which keeps prepares a few seconds apart from handing out the same port."
  [config {worktree-name :name git-ref :ref :keys [detach?]}]
  (let [registry (read-registry config)
        ports (ports-for {:name worktree-name :registry registry :ports (:worktree/ports config) :in-use? port-listening?})]
    (write-registry! config (assoc registry worktree-name {:worktree/ref git-ref
                                                           :worktree/detached? (boolean detach?)
                                                           :worktree/ports ports}))
    ports))

(defn check
  "Returns why `prepare!` with `opts` would be refused, or nil."
  [config opts]
  (name-problem (assoc opts
                       :registry (read-registry config)
                       :control? (contains? config :worktree/control))))

(defn prepare!
  "Stops the worktree `name`'s processes, then puts `ref` in it, records its ports, runs the config's prepare steps, and adds the exclude lines. `detach?` is for the control; `new-branch?` creates the branch `ref` from the source's current commit."
  [{:worktree/keys [source remote claude-code] worktrees-dir :worktree/dir :as config}
   {worktree-name :name git-ref :ref :keys [detach? new-branch?] :as opts}]
  (when-let [problem (check config opts)]
    (fail problem {:name worktree-name :ref git-ref}))
  (stop! config worktree-name)
  (p/shell "git" "-C" source "fetch" remote)
  (let [path (worktree-path config worktree-name)]
    (fs/create-dirs worktrees-dir)
    (apply p/shell (worktree-args {:source source
                                   :exists? (fs/exists? path)
                                   :path path
                                   :ref git-ref
                                   :detach? detach?
                                   :new-branch? new-branch?
                                   :base (when new-branch? (sh-out "git" "-C" source "rev-parse" "HEAD"))}))
    (let [ports (register! config opts)]
      (exclude! config)
      (when claude-code (give-claude-settings! config path))
      (run-steps! config path)
      (fs/delete-if-exists (fs/path path ".nrepl-port"))
      (println "Worktree" worktree-name "is ready at" git-ref "on ports" (pr-str ports) "-"
               (sh-out "git" "-C" path "log" "--oneline" "-1")))))

(defn- running-names
  "Returns the names of the worktrees in `registry` whose processes run."
  [config registry]
  (set (filter #(seq (worktree-pids config registry %)) (keys registry))))

(defn- refs [{:worktree/keys [source]} & patterns]
  (some-> (apply sh-out "git" "-C" source "for-each-ref" "--sort=-committerdate" "--format=%(refname:short)" patterns)
          not-empty
          str/split-lines))

(defn picker-data
  "Returns what the picker of `kind` lists: `:worktree` the branches to start worktrees on and the source's current branch, `:control` the refs for the control, `:running` the running worktrees other than the control."
  [{:worktree/keys [source remote control] worktrees-dir :worktree/dir :as config} kind]
  (let [registry (read-registry config)]
    (case kind
      :worktree {:current-branch (sh-out "git" "-C" source "branch" "--show-current")
                 :items (worktree-items {:branches (refs config "refs/heads")
                                         :checked-out (checked-out-branches (worktree-list config))
                                         :worktrees-path (str (fs/canonicalize worktrees-dir))
                                         :running (running-names config registry)})}
      :control (if control
                 {:items (map (fn [git-ref] {:worktree/ref git-ref})
                              (control-refs (or (:default-ref control) (str remote "/main"))
                                            remote
                                            (refs config "refs/remotes" "refs/heads")))}
                 (fail "This project has no control." {}))
      :running {:items (keep (fn [running-name]
                               (when (not= control-name running-name)
                                 (assoc (get registry running-name) :worktree/name running-name)))
                             (sort (running-names config registry)))}
      (fail "Name a picker: worktree, control or running." {}))))

(defn port
  "Returns the port `port-name` of the worktree `worktree`, or of the checkout holding `cwd` when none is named. The source checkout answers with the port's `:main`."
  [{:worktree/keys [ports] :as config} cwd {:keys [port-name worktree]}]
  (let [{:port/keys [problem] chosen :port/name} (port-name-for ports port-name)
        checkout (or worktree (config/checkout-name config (str (fs/canonicalize cwd))))]
    (cond
      problem (fail problem {})
      (and worktree (not (valid-name? worktree))) (fail (str "No worktree can be named " worktree ".") {})
      (nil? checkout) (or (get-in ports [chosen :main])
                          (fail (str "The port " (name chosen) " has no :main number for the source checkout.") {}))
      :else (or (get-in (read-registry config) [checkout :worktree/ports chosen])
                (fail (str "The worktree " checkout " has no port " (name chosen) " in the registry. Prepare it first.") {})))))

(defn- yes? [answer]
  (contains? #{"y" "yes"} (str/lower-case (str/trim (str answer)))))

(defn- print-removal [{worktree-name :name :keys [path branch pids only-here]}]
  (println "This removes the worktree" worktree-name "in" path "and its entry in the registry")
  (when (seq pids)
    (println "Its processes run, pids" (str/join " " pids) "- they are stopped first."))
  (when branch
    (println "It also deletes the branch" branch)
    (if (seq only-here)
      (do (println "These commits are on no other branch and not on a remote, and go with it:")
          (println only-here))
      (println "Every commit on it is also on another branch or on a remote."))))

(defn remove!
  "Removes the worktree `name`: stops its processes, removes the git worktree and its folder, deletes its branch, and drops its registry entry, after listing what goes and asking. Refuses while the worktree has changes git hasn't committed."
  [{:worktree/keys [source] :as config} worktree-name]
  (when (str/blank? worktree-name)
    (fail "Usage: worktree remove <name>" {}))
  (let [path (check-existing-worktree config worktree-name)]
    (when-let [changes (not-empty (sh-out "git" "-C" path "status" "--porcelain"))]
      (fail (str "The worktree " worktree-name " has changes git hasn't committed. Commit or discard them first:\n" changes) {:name worktree-name}))
    (let [branch (not-empty (sh-out "git" "-C" path "branch" "--show-current"))
          info {:name worktree-name
                :path path
                :branch branch
                :pids (worktree-pids config worktree-name)
                :only-here (when branch
                             (sh-out "git" "-C" source "log" "--oneline" branch
                                     "--not" (str "--exclude=refs/heads/" branch) "--branches" "--remotes" "--"))}]
      (print-removal info)
      (print "Remove it? [y/N] ")
      (flush)
      (if-not (yes? (read-line))
        (println "Nothing removed.")
        (do (stop! config worktree-name)
            (doseq [args (removal-args {:source source :path path :branch branch})]
              (apply p/shell args))
            (write-registry! config (read-registry config))
            (println "The worktree" worktree-name "is removed."))))))

(defn- cwd []
  (System/getProperty "user.dir"))

(defn print-config
  "Prints the config that applies in the folder `dir`, with its defaults filled in."
  [dir]
  (pprint/pprint (into (sorted-map) (config/config dir))))

(defn- run-subcommand! [subcommand args]
  (let [config (config/config (cwd))
        {[arg] :args opts :opts} (cli/parse-args args cli-spec)]
    (case subcommand
      "prepare" (prepare! config (args->opts args))
      "check" (some-> (check config (args->opts args)) println)
      "stop" (when-not (stop! config arg)
               (println "No processes of worktree" arg "were running."))
      "remove" (remove! config arg)
      "items" (prn (picker-data config (some-> arg keyword)))
      "port" (println (port config (cwd) {:port-name arg :worktree (:worktree opts)}))
      "name" (let [{:worktree/keys [problem] worktree-name :worktree/name}
                   (branch-worktree-name arg (contains? config :worktree/control))]
               (if problem
                 (fail problem {:branch arg})
                 (println worktree-name)))
      (fail usage {}))))

(defn exec!
  "Runs the worktree subcommand in `args`, from the working folder."
  [[subcommand & args]]
  (if (= "config" subcommand)
    (print-config (cwd))
    (run-subcommand! subcommand args)))
