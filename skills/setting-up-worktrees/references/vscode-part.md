# A project's VS Code part for worktrees

A project's VS Code part starts and stops its worktrees. It is one Joyride namespace in the project's `.joyride/src`, plus a few static VS Code tasks that call it. The bbg commands do the file and git work: preparing a checkout, checking a name, stopping processes, and answering for names and ports. The Joyride namespace does what only VS Code can: run processes as tasks, show pickers and progress, and connect Calva. The excerpts come from a version used every day, by PEZ and by agents. `;; PROJECT:` marks every place a project fills in its own values. `bbg` stands for `bb` in a project on the git dep.

The flow of a start: check whether the name can take the ref, then end what runs for that worktree, then prepare the checkout, then start its processes, then wait until they listen, then connect Calva. Each step reports to a progress notification and to an atom agents can read.

## 1. Static tasks reach Joyride through command inputs

VS Code tasks are static, so the human's entry points are tasks whose command echoes a command input, and the input runs Joyride code.

```json
{"id": "worktreeStart", "type": "command", "command": "joyride.runCode",
 "args": "(require '[worktrees] :reload) (worktrees/start-worktree!) \"ok\""}

{"label": "worktree: start", "type": "shell",
 "command": "echo ${input:worktreeStart}", "problemMatcher": [],
 "presentation": {"reveal": "silent", "close": true, "panel": "shared", "showReuseMessage": false}}
```

Why: there's no other way to put a Joyride function in the task list. `:reload` makes edits to the namespace take effect without restarting anything. The task's terminal only shows the echo, so it closes silently. A project with a control has four of these: start and stop for worktrees, and start and stop for the control. Without one, two.

## 2. Processes run as tasks created from the API, named per worktree

```clojure
(defn shell-task
  "Returns a VS Code task named `label`. With `args`, each argument is quoted for the shell; else `command` is a shell command line."
  [{:keys [label cwd command args env background? reveal source close?]}]
  (let [source (or source "worktrees")
        options (clj->js (cond-> {:cwd cwd} env (assoc :env env)))
        execution (if args
                    (vscode/ShellExecution. (first args) (clj->js (map str (rest args))) options)
                    (vscode/ShellExecution. command options))
        task (vscode/Task. #js {:type "shell" :label label}
                           vscode/TaskScope.Workspace label source execution)]
    (set! (.-isBackground task) (boolean background?))
    (set! (.-presentationOptions task)
          #js {:reveal (or reveal vscode/TaskRevealKind.Always)
               :panel vscode/TaskPanelKind.Dedicated
               :group source
               :close (boolean close?)})
    task))

(defn- labels [name]
  {:prepare (str "worktree prepare: " name)
   :repl (str "worktree repl: " name)            ;; PROJECT: one label per process the worktree runs
   :runtime-page (str "worktree runtime page: " name)
   :stop (str "worktree stop: " name)})

(defn run-to-end+
  "Runs `task` and returns a promise of its exit code."
  [task]
  (js/Promise.
   (fn [resolve reject]
     (let [!sub (atom nil)]
       (reset! !sub (vscode/tasks.onDidEndTaskProcess
                     (fn [e]
                       (when (= (.-name task) (.. e -execution -task -name))
                         (.dispose @!sub)
                         (resolve (.-exitCode e))))))
       (-> (vscode/tasks.executeTask task)
           (.catch (fn [e] (.dispose @!sub) (reject e))))))))

(defn terminate-tasks! [labels]
  (doseq [label labels
          execution (filter #(= label (some-> % .-task .-name)) (.-taskExecutions vscode/tasks))]
    (.terminate execution)))
```

Why: VS Code runs a task with a given label only once at a time, so every label carries the worktree's name, and two worktrees' processes don't collide. Tasks are found and ended again by label. Prefer `args` to `command`: each argument gets quoted, so EDN and `-e` forms don't need hand-written nested quoting. Tasks created from the API are blind. They can name a problem matcher but not define one, and neither Joyride nor an agent can read their terminal output. So whether a process is ready is decided from outside, as in section 5, and the prepare step's success comes from its exit code.

## 3. The start order

```clojure
(defn ^:async start-steps!
  [{:worktree/keys [name] :as worktree} progress]
  #_{:clj-kondo/ignore [:redundant-do]}
  (do ;; the do makes every await count, see "An ^:async body awaits only its last form"
    (let [problem (str/trim (await (exec+ "bbg" (into ["worktree" "check"] (prepare-args worktree)) (project-dir))))]
      (when (seq problem)
        (throw (js/Error. problem))))
    (.report progress #js {:message "stopping what runs"})
    (await (end-tasks+ name))
    (.report progress #js {:message "preparing the checkout"})
    (let [exit-code (await (run-to-end+ (prepare-task worktree)))]
      (when-not (zero? exit-code)
        (throw (js/Error. (str "Preparing failed. The terminal \"" (:prepare (labels name)) "\" says why.")))))
    (let [port (await (worktree-port+ name))]                    ;; asks `bbg worktree port <port-name> --worktree <name>`
      (.report progress #js {:message "starting the JVM"})       ;; PROJECT: the processes a worktree runs
      (await (vscode/tasks.executeTask (repl-task name port)))
      (await (wait-for+ "the nREPL server"
                        #(if-let [nrepl (nrepl-port name)] (port-listening?+ nrepl) false)
                        (* 5 60 1000)))                         ;; PROJECT: how long the processes take to start
      (await (connect-through-runtime-page+ name port progress)))
    :ok))

(defn- end-tasks+ [name]
  (let [{:keys [repl runtime-page]} (labels name)]
    (-> (disconnect+ name)
        (.then (fn [_]
                 (terminate-tasks! [repl runtime-page])
                 (sleep+ 1000))))))
```

An `^:async` body awaits only its last form. In an `^:async` function with several body forms, an `await` in a form that isn't the last one isn't waited for, and the function resolves at once. Inside one `do` or `let` every `await` counts. So the body of every `^:async` function with more than one form goes in a `do`, with `#_{:clj-kondo/ignore [:redundant-do]}`, since clj-kondo calls that `do` redundant. Without it, a stop resolves before the processes have ended, and a start prepares while the old tasks still run. Measured: `(defn ^:async f [] (await (sleep 500)) :ok)` resolves in a few milliseconds, and the same body in a `do` in about 500 ms.

Why: the check comes first, so a start that's going to be refused stops nothing. A refused or failed start must leave the other running worktrees alone. Calva is disconnected before its processes are ended, so it doesn't spend time reconnecting to a dying server. Prepare runs as a visible task, so when it fails, git's or npm's reason is in a terminal the human can read, and the error message names that terminal.

## 4. Messages are shown outside the awaited steps

```clojure
(defonce !starts (atom {}))

(defn start!
  [{:worktree/keys [name] :as worktree}]
  (swap! !starts assoc name {:start/status :starting})
  (vscode/window.withProgress
   #js {:location vscode/ProgressLocation.Notification :title (str "Worktree " name)}
   (fn [progress]
     (let [reporter #js {:report (fn [m]
                                   (swap! !starts assoc-in [name :start/step] (.-message m))
                                   (.report progress m))}]
       (-> (start-steps! worktree reporter)
           (.then (fn [result]
                    (swap! !starts assoc name {:start/status :running :start/port ...})
                    (vscode/window.showInformationMessage (str "Worktree " name " runs on ..."))
                    result))
           (.catch (fn [e]
                     (swap! !starts assoc name {:start/status :failed :start/error (.-message e)})
                     (vscode/window.showErrorMessage (str "Worktree " name ": " (.-message e)))
                     nil)))))))
```

Why: this is a trap that has cost a commit before. `showInformationMessage` returns a promise that resolves when the human closes the message. Inside an `^:async` function, awaiting it, even indirectly, keeps the whole start from finishing until someone clicks. So the messages go in a plain promise chain outside the async steps, and nobody awaits them. The progress reporter also writes each step into `!starts`, which is what agents read.

## 5. Readiness is checked from outside

```clojure
(defn wait-for+
  "Polls `pred+` every second until it is truthy. Rejects after `timeout-ms`."
  [what pred+ timeout-ms]
  (let [deadline (+ (js/Date.now) timeout-ms)
        poll (fn poll []
               (-> (js/Promise.resolve (pred+))
                   (.then (fn [ok?]
                            (cond
                              ok? true
                              (> (js/Date.now) deadline) (throw (js/Error. (str "Gave up waiting for " what)))
                              :else (.then (sleep+ 1000) poll))))))]
    (poll)))

(defn port-listening?+ [port]
  (js/Promise.
   (fn [resolve]
     (let [socket (net/connect #js {:host "127.0.0.1" :port port})]
       (.on socket "connect" (fn [] (.destroy socket) (resolve true)))
       (.on socket "error" (fn [_] (resolve false)))))))
```

Why: the tasks are blind (section 2), so a process is ready when its port answers. For an nREPL server that writes `.nrepl-port`, the check waits for the file and then for the port. When a worktree runs several servers, or servers on ports bbg hands out, wait on each of the worktree's ports from `bbg worktree port` instead, since several servers in one folder may each write `.nrepl-port`.

## 6. An agent API that returns at once

```clojure
(defn start-status [name] (get @!starts name))

(defn ^:async start-unless-running! [{:worktree/keys [name] :as worktree}]
  (if (await (running?+ name))
    {:start/status :already-running}
    (do (start! worktree)
        (start-status name))))

(defn start-branch!
  ([branch] (start-branch! branch {}))
  ([branch {:keys [new-branch?]}]
   (start-unless-running! {:worktree/ref branch
                           :worktree/name (await (worktree-name+ branch))   ;; asks `bbg worktree name <branch>`
                           :worktree/new-branch? (boolean new-branch?)})))
```

Why: a start takes minutes, and VS Code notifications don't reach an agent. Agents call `start-branch!` through Joyride's MCP server, get `:starting` back at once, and poll `start-status` about once a minute. A worktree that already runs is left alone, since another agent or the human may be working in it.

## 7. Connecting Calva under the worktree's name

```clojure
(defn- connect! [name port]
  (vscode/commands.executeCommand
   "calva.connect"
   (clj->js {:host "localhost"
             :port port
             :connectSequence {:name name
                               :projectType "deps.edn"                  ;; PROJECT
                               :cljsType "shadow-cljs"                  ;; PROJECT
                               :projectRootPath ["worktrees" name]      ;; PROJECT: where worktrees live
                               :autoSelectForConnect true
                               :menuSelections {:cljAliases ["dev" "test"]          ;; PROJECT
                                                :cljsLaunchBuilds ["app"]           ;; PROJECT
                                                :cljsDefaultBuild "app"}            ;; PROJECT
                               :afterPrimaryReplConnectedCode ["..."]               ;; PROJECT, optional
                               :replSessionNames {:primary (str name "-clj")
                                                  :secondary (str name "-cljs")}
                               :replSessionFilePatterns {:primary ["*.clj" "*.cljc" "*.edn"]
                                                         :secondary ["*.cljs"]}}})))

(defn- disconnect+ [name]
  (if-let [connection (some #(when (some #{(str name "-clj")} (.-replSessionKeys %)) %)
                            (some-> (calva-repl) .listConnections))]
    (vscode/commands.executeCommand "calva.disconnect" #js {:clientKey (.-clientKey connection)})
    (js/Promise.resolve nil)))
```

Why: the session names carry the worktree's name, so agents and the human always know which checkout an evaluation goes to, and the main checkout's sessions are never touched. `replSessionFilePatterns` together with `projectRootPath` route evaluations from the worktree's files to its own sessions. A connection is found again through Calva's API by its session keys. A project with several REPLs per worktree, such as a Babashka nREPL beside a Squint one, makes one connect per REPL, each with its own pair of session names.

### Several REPLs per worktree

A worktree that runs several REPL servers, such as a Babashka nREPL beside a Squint one, makes one Calva connection per server. Start from the project's own `calva.replConnectSequences` in its workspace settings, which already connect to the main checkout, and for each one:

- Keep its `projectType` and menu selections.
- Set `projectRootPath` to the worktree's folder, given relative to the workspace folder the sequence lives in, the same way the existing sequence gives the main checkout's.
- Name its sessions `<worktree>-<repl>`, such as `x-bb`, and keep the existing sequence's file patterns so evaluations route by file type.
- Pass the port from `bbg worktree port <port-name> --worktree <name>`, rather than having Calva read a port file.

`disconnect+` then finds a worktree's connections by any of its session keys, not only `<name>-clj`, and disconnects each. When the project already has a Joyride script that connects its REPLs, extend that script with the worktree's name and ports rather than writing a second one.

## 8. Connecting through a runtime page (shadow-cljs only)

```clojure
(defn- connect-through-runtime-page+ [name port progress]
  (let [!failure (atom nil)
        report! (fn [message] (.report progress #js {:message message}))]
    (report! "opening the runtime page")
    (-> (vscode/tasks.executeTask (runtime-page-task name port))
        (.then (fn [_]
                 (report! "asking Calva to connect")
                 (-> (connect! name (nrepl-port name))
                     (.catch (fn [e] (reset! !failure e))))
                 (report! "waiting for Calva's sessions")
                 (wait-for+ "the Calva connection"
                            #(if-let [e @!failure] (throw e) (connected? name))
                            (* 3 60 1000))))
        (.finally (fn []
                    (report! "closing the runtime page")
                    (terminate-tasks! [(:runtime-page (labels name))]))))))
```

The runtime page is a small Node script run as a background task, Playwright in headless Chromium:

```js
// PROJECT: where Playwright is found, and the page that loads the build without a login
const { chromium } = require(playwrightPath);
// For each attempt: load the page and wait for the console to say /shadow-cljs: .*ready/.
// Reload while the first build is still compiling, give up after a limit,
// and close the browser on SIGTERM/SIGINT/SIGHUP.
```

Why: Calva only finishes a shadow-cljs connection when the build has a runtime, and right after a start there's no browser tab. The page closes again as soon as Calva is connected, because shadow-cljs keeps a REPL session on its first runtime until that runtime goes away. A page left open would take the human's evaluations away from their own tab. Waiting for Calva's sessions also checks a failure atom, since `calva.connect` can fail without the wait finding out otherwise. Projects without shadow-cljs skip this section and connect directly.

## 9. A picker that takes typed values and refreshes in the background

```clojure
(defn- pick+
  "Shows a QuickPick of `items`, maps with `:label`, `:description` and `:data`, and returns a promise of the chosen item's `:data`, or nil. `typed-item` makes an item of a typed value no item has as its label. `refresh+` returns a promise of new items, shown when it resolves."
  [{:keys [title placeholder items typed-item refresh+]}]
  (js/Promise.
   (fn [resolve]
     (let [qp (vscode/window.createQuickPick)
           !items (atom items)
           !shown (atom [])
           render! (fn []
                     (let [value (str/trim (.-value qp))
                           typed (when (and typed-item (seq value)
                                            (not-any? #(= value (:label %)) @!items))
                                   [(typed-item value)])
                           shown (vec (concat typed @!items))]
                       (reset! !shown shown)
                       (set! (.-items qp) (->quick-pick-items shown))))]
       (set! (.-title qp) title)
       (set! (.-placeholder qp) placeholder)
       (set! (.-matchOnDescription qp) true)
       (.onDidChangeValue qp (fn [_] (render!)))
       (.onDidAccept qp (fn []
                          (let [label (some-> (first (.-selectedItems qp)) .-label)]
                            (resolve (:data (some #(when (= label (:label %)) %) @!shown)))
                            (.hide qp))))
       (.onDidHide qp (fn [] (resolve nil) (.dispose qp)))
       (render!)
       (.show qp)
       (when refresh+
         (set! (.-busy qp) true)
         (-> (refresh+)
             (.then (fn [items] (reset! !items items) (render!)))
             (.catch (fn [_] nil))
             (.finally (fn [] (set! (.-busy qp) false)))))))))

(defn- fetch-then+ [items-fn+]
  (-> (exec+ "git" ["fetch" remote] source-dir)   ;; PROJECT: the remote and the source checkout
      (.catch (fn [_] nil))
      (.then (fn [_] (items-fn+)))))
```

Why: `createQuickPick` rather than `showQuickPick`, because the human has to be able to type a value no item has, such as a new branch name or a ref, and see what it will do ("new branch from <current>, in the worktree <name>"). The list shows at once from local refs. `git fetch` runs behind it while the picker shows as busy, and the items are replaced when the fetch is done, so a slow connection doesn't hold the human up. The items come from `bbg worktree items worktree|control|running`, so the rules for which branches are listed live once, in tested code.

## 10. Ask bbg, don't read its files

The commands print text, so a port from `bbg worktree port` is a string until the VS Code part parses it, which it does before comparing or storing it.

Names, ports and the picker data come from `bbg worktree name <branch>`, `bbg worktree items ...` and `bbg worktree port <port-name> --worktree <name>`, and the check and prepare go through `bbg worktree check|prepare`. Whether a worktree runs comes from `:worktree/running?` in `items`, or from the exit code of `bbg worktree running <name>`, 0 when it runs, 1 when it doesn't and 2 when there's no such worktree, which also answers for the control, so the picker, the start and the stop agree with bbg's rule. Why: a VS Code part that reads the registry or defines the naming rule itself keeps a contract in two languages, and drifts from the commands. The one thing Joyride still reads itself is a worktree's `.nrepl-port`, since that file belongs to the running process rather than to bbg.

## 11. Stopping

```clojure
(defn ^:async stop! [name]
  #_{:clj-kondo/ignore [:redundant-do]}
  (do ;; the do makes every await count
    (await (end-tasks+ name))
    (await (run-to-end+ (stop-task name)))       ;; runs `bbg worktree stop <name>`
    (await (wait-for+ "the processes to stop" #(.then (running?+ name) not) (* 60 1000)))
    :ok))
```

Why: it resolves only once the port no longer answers, so a start right after a stop finds the worktree stopped. `bbg worktree stop` ends a process only when its working folder is that worktree, so it never ends the main checkout's process by mistake.

## What a project fills in

The processes a worktree runs, and their commands, environment and ports. Where the worktrees live (`projectRootPath`). The Calva connect sequence or sequences, with project type, aliases, builds and after-connect code. Whether a runtime page is needed, which page it opens, and where Playwright is. The timeouts. The remote and the source checkout for the fetch. The picker titles. Whether there's a control, and so its two extra tasks and its ref picker.
