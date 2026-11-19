(ns bbg.worktree-e2e-test
  "Runs the worktree commands in scratch projects with real git and real processes. Slow, so it runs from its own task, not the unit test watcher."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [bbg.worktree.config :as config]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- git! [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string}
         "git" "-c" "user.email=t@example.com" "-c" "user.name=t" args))

(def ^:private without-user-git-config
  {"GIT_CONFIG_GLOBAL" "/dev/null"
   "XDG_CONFIG_HOME" (str (fs/path (fs/temp-dir) "bbg-worktree-e2e-no-config"))})

(defn- git-status [dir]
  (:out (p/sh {:dir (str dir) :extra-env without-user-git-config} "git" "status" "--porcelain")))

(defn- bbg-in
  "Runs a bbg task through bbg's own bb.edn in `dir`, with `input` on stdin and git's user-level config left out, returning exit, out and err."
  [dir input & args]
  (let [bb-edn (or (System/getProperty "babashka.config")
                   (str (fs/path (fs/home) ".config" "bbg" "bb.edn")))]
    (select-keys (apply p/sh {:dir (str dir)
                              :in input
                              :extra-env without-user-git-config}
                        "bb" "--config" bb-edn args)
                 [:exit :out :err])))

(defn- bbg [dir & args]
  (apply bbg-in dir "" args))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- in-temp-dir [f]
  (let [dir (fs/create-temp-dir)]
    (try
      (f (str (fs/canonicalize dir)))
      (finally
        (fs/delete-tree dir)))))

(defn- project!
  "Creates a project at `dir`, cloned from a bare origin next to it, with `files` committed on main."
  [dir files]
  (let [origin (str dir "-origin.git")
        seed (str dir "-seed")]
    (fs/create-dirs seed)
    (git! seed "init" "-q" "-b" "main")
    (doseq [[path content] files]
      (fs/create-dirs (fs/parent (fs/path seed path)))
      (spit (str (fs/path seed path)) content))
    (git! seed "add" "-A")
    (git! seed "commit" "-q" "--allow-empty" "-m" "init")
    (git! seed "clone" "-q" "--bare" seed origin)
    (git! (fs/parent dir) "clone" "-q" origin dir)
    dir))

(defn- listen!
  "Starts a process in `dir` that listens on `port`, and returns it."
  [dir port]
  (let [proc (p/process {:dir (str dir)} "bb" "-e"
                        (str "(require '[org.httpkit.server :as s]) (s/run-server (fn [_] {:body \"x\"}) {:port " port "}) @(promise)"))]
    (loop [tries 50]
      (when (and (pos? tries)
                 (not (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] true)
                           (catch Exception _ false))))
        (Thread/sleep 100)
        (recur (dec tries))))
    proc))

(defn- registry [project]
  (edn/read-string (slurp (str (fs/path project "worktrees" ".state" "registry.edn")))))

(deftest in-project-layout
  (in-temp-dir
   (fn [tmp]
     (let [[main-port first-port control-port] (repeatedly 3 free-port)
           project (project! (str tmp "/project")
                             {"worktrees.edn" (pr-str {:worktree/ports {:app {:first first-port :main main-port :control control-port}}
                                                       :worktree/control {:default-ref "origin/main"}
                                                       :worktree/prepare [[:share "deps" {:same "lock.txt" :install ["mkdir" "deps"]}]
                                                                          [:run ["touch" "prepared"]]]})
                              "lock.txt" "1"
                              ".gitignore" "/deps/\n/prepared\n"})
           x (str project "/worktrees/x")]
       (fs/create-dirs (fs/path project "deps"))
       (testing "prepare with a new branch"
         (let [{:keys [exit out]} (bbg project "worktree" "prepare" "x" "pez/x" "--new-branch")]
           (is (zero? exit) out)
           (is (fs/exists? (fs/path x "prepared")) "The :run step ran in the worktree")
           (is (fs/sym-link? (fs/path x "deps")) "The :share step linked the source's folder")
           (is (= (str (fs/path project "deps")) (str (fs/read-link (fs/path x "deps")))) "The link points at the source's folder")
           (is (= {:worktree/ref "pez/x" :worktree/detached? false :worktree/ports {:app first-port}}
                  (get (registry project) "x"))
               "The registry holds the ref and the ports")
           (let [exclude (slurp (str (fs/path project ".git" "info" "exclude")))]
             (is (str/includes? exclude "/deps\n") "The shared folder is excluded")
             (is (str/includes? exclude "/worktrees/\n") "The worktrees folder is excluded"))
           (is (str/blank? (git-status project))
               "The main checkout shows nothing untracked")))
       (testing "the control"
         (let [{:keys [exit out]} (bbg project "worktree" "prepare" "control" "origin/main" "--detach")]
           (is (zero? exit) out)
           (is (= {:app control-port} (get-in (registry project) ["control" :worktree/ports])) "The control's fixed number")))
       (testing "check"
         (is (str/includes? (:out (bbg project "worktree" "check" "foo" "pez/bar")) "is named bar")
             "A name that isn't the branch's last segment")
         (is (str/blank? (:out (bbg project "worktree" "check" "x" "pez/x")))
             "Restarting a worktree on its own branch is fine")
         (is (= "brand-new\n" (:out (bbg project "worktree" "name" "pez/brand-new")))
             "The name a branch's worktree would get, before any prepare")
         (is (= 1 (:exit (bbg project "worktree" "name" "pez/control")))
             "A branch whose worktree can't have its name"))
       (testing "items and port"
         (let [items (edn/read-string (:out (bbg project "worktree" "items" "worktree")))]
           (is (= "main" (:current-branch items)))
           (is (some #(= {:worktree/ref "pez/x" :worktree/name "x" :worktree/exists? true :worktree/running? false} %)
                     (:items items))
               "The worktree's branch, existing and stopped"))
         (is (= "origin/main" (:worktree/ref (first (:items (edn/read-string (:out (bbg project "worktree" "items" "control"))))))))
         (is (= (str first-port) (str/trim (:out (bbg (str x "/") "worktree" "port")))) "From inside the worktree")
         (is (= (str main-port) (str/trim (:out (bbg project "worktree" "port")))) "From the source checkout, the :main number")
         (is (= (str first-port) (str/trim (:out (bbg project "worktree" "port" "--worktree" "x")))) "A named worktree"))
       (testing "stop"
         (let [listener (listen! x first-port)
               idle (p/process {:dir x} "sleep" "60")
               outside-port (free-port)
               outside (listen! project outside-port)]
           (try
             (is (some #(= {:worktree/ref "pez/x" :worktree/name "x" :worktree/exists? true :worktree/running? true} %)
                       (:items (edn/read-string (:out (bbg project "worktree" "items" "worktree")))))
                 "A listening process makes the worktree running")
             (testing "a failed or refused prepare leaves a running worktree alone"
               (is (pos? (:exit (bbg project "worktree" "prepare" "foo" "pez/bar"))))
               (is (pos? (:exit (bbg project "worktree" "prepare" "y" "pez/y"))) "A branch that doesn't exist")
               (is (p/alive? listener)))
             (is (zero? (:exit (bbg project "worktree" "running" "x"))) "running answers through its exit code")
             (is (zero? (:exit (bbg project "worktree" "stop" "x"))))
             (is (= 1 (:exit (bbg project "worktree" "running" "x"))) "and after a stop")
             (is (= 1 (:exit (bbg project "worktree" "running" "control"))) "The control is asked the same way")
             (is (= 2 (:exit (bbg project "worktree" "running" "nope"))) "No such worktree is told apart from a stopped one")
             (is (not (p/alive? listener)) "The worktree's listening process is ended")
             (is (p/alive? idle) "A process in the worktree that listens on nothing is left alone")
             (is (p/alive? outside) "A listening process outside the worktree is left alone")
             (finally
               (p/destroy-tree idle)
               (p/destroy-tree outside)
               (p/destroy-tree listener)))))
       (testing "remove"
         (is (pos? (:exit (bbg project "worktree" "stop" ".state"))) "The state folder is no worktree")
         (is (pos? (:exit (bbg project "worktree" "remove" ".state"))) "The state folder can't be removed")
         (is (pos? (:exit (bbg project "worktree" "stop" "../../x"))) "A name that leaves the worktrees folder")
         (is (pos? (:exit (bbg project "worktree" "stop"))) "No name")
         (is (pos? (:exit (bbg project "worktree" "port" "--worktree" "../x"))) "A port for a name that leaves the worktrees folder")
         (is (str/includes? (:out (bbg project "worktree" "check" "x")) "Usage") "check without a ref")
         (is (pos? (:exit (bbg project "worktree" "items"))) "items without a picker")
         (spit (str (fs/path x "new.txt")) "x")
         (let [{:keys [exit out err]} (bbg project "worktree" "remove" "x")]
           (is (pos? exit) "Uncommitted changes refuse removal")
           (is (str/includes? (str out err) "changes git hasn't committed")))
         (fs/delete (fs/path x "new.txt"))
         (let [{:keys [out]} (bbg-in project "n\n" "worktree" "remove" "x")]
           (is (str/includes? out "Nothing removed."))
           (is (fs/exists? x)))
         (let [{:keys [exit out]} (bbg-in project "y\n" "worktree" "remove" "x")]
           (is (zero? exit) out)
           (is (str/includes? out "deletes the branch pez/x"))
           (is (not (fs/exists? x)) "The folder is gone")
           (is (not (contains? (registry project) "x")) "The registry entry is gone")
           (is (str/blank? (:out (p/sh {:dir project} "git" "branch" "--list" "pez/x"))) "The branch is gone")))))))

(deftest dev-repo-layout
  (in-temp-dir
   (fn [tmp]
     (let [port (free-port)
           source (project! (str tmp "/source") {"README" "x"})
           dev (str tmp "/dev")
           x (str dev "/worktrees/x")]
       (fs/create-dirs (fs/path dev ".claude" "skills"))
       (git! dev "init" "-q")
       (spit (str (fs/path dev "worktrees.edn"))
             (pr-str {:worktree/source "../source"
                      :worktree/ports {:app {:first port}}
                      :worktree/claude-code {:dev-dir "."}}))
       (let [{:keys [exit out err]} (bbg dev "worktree" "prepare" "x" "pez/x" "--new-branch")]
         (is (zero? exit) (str out err)))
       (is (fs/exists? (fs/path x "README")) "The source's worktree, in the dev folder")
       (is (= (str (fs/path dev ".claude" "skills")) (str (fs/read-link (fs/path x ".claude" "skills"))))
           "The skills link points at the dev folder's")
       (is (str/includes? (slurp (str (fs/path x ".claude" "settings.local.json"))) dev)
           "The dev folder is an added directory")
       (let [exclude (slurp (str (fs/path source ".git" "info" "exclude")))]
         (is (str/includes? exclude "/.claude/skills\n"))
         (is (str/includes? exclude "/.claude/settings.local.json\n"))
         (is (not (str/includes? exclude "/worktrees/")) "The worktrees folder isn't in the source, so it isn't excluded there"))
       (is (str/blank? (git-status x))
           "The worktree shows nothing untracked")
       (is (= (str port) (str/trim (:out (bbg (str x "/") "worktree" "port"))))
           "port from inside a worktree of the dev-repo layout")
       (let [{:keys [exit out err]} (bbg-in dev "y\n" "worktree" "remove" "x")]
         (is (zero? exit) (str out err))
         (is (not (fs/exists? x)) "A worktree with Claude Code links is removed"))))))

(defn- small-project!
  "Creates an in-project layout with one worktree, x, prepared."
  [tmp]
  (let [project (project! (str tmp "/project")
                          {"worktrees.edn" (pr-str {:worktree/ports {:app {:first (free-port) :main (free-port)}}})})]
    (bbg project "worktree" "prepare" "x" "pez/x" "--new-branch")
    project))

(deftest locks
  (in-temp-dir
   (fn [tmp]
     (let [project (small-project! tmp)
           x (str project "/worktrees/x")
           log (str tmp "/log")
           run #(p/process {:dir % :extra-env without-user-git-config}
                           "bb" "--config" (or (System/getProperty "babashka.config")
                                               (str (fs/path (fs/home) ".config" "bbg" "bb.edn")))
                           "lock" "demo" "--"
                           "sh" "-c" (str "echo start $(basename $PWD) >> " log "; sleep 1; echo end $(basename $PWD) >> " log))
           a (run project)
           _ (loop [tries 100]
               (when (and (pos? tries)
                          (not (and (fs/exists? log) (str/includes? (slurp log) "start project"))))
                 (Thread/sleep 100)
                 (recur (dec tries))))
           b (run x)]
       @a @b
       (let [lines (str/split-lines (slurp log))]
         (is (= 4 (count lines)))
         (is (= [["start" "end"] ["start" "end"]] (map #(map (fn [line] (first (str/split line #" "))) %) (partition 2 lines)))
             "Two runs from two checkouts take turns rather than overlap")
         (is (= #{"project" "x"} (set (map #(second (str/split % #" ")) lines)))
             "Each run ran in the checkout it was started from"))
       (is (= "x" (slurp (str (fs/path project "worktrees" ".state" "demo.lock.holder"))))
           "The last holder is recorded")
       (is (= 3 (:exit (bbg project "lock" "demo" "--" "sh" "-c" "exit 3")))
           "The command's exit code is passed through")
       (is (str/includes? (:out (bbg project "lock" "demo" "--worktree" "x" "--" "pwd")) "/worktrees/x")
           "A named worktree overrides the working folder")
       (is (= "a b\n" (:out (bbg project "lock" "demo" "--" "printf" "%s %s\n" "a" "b")))
           "The command's words are passed as they are, not split at spaces")))))

(deftest claims
  (in-temp-dir
   (fn [tmp]
     (let [project (small-project! tmp)
           x (str project "/worktrees/x")
           claim-file (str (fs/path project "worktrees" ".state" "shared.claim"))]
       (let [{:keys [exit out]} (bbg x "claim" "take" "shared" "agent-a")]
         (is (zero? exit) "A free claim is taken")
         (is (= (str "You hold shared, agent-a. Release it with: " (config/runner) " claim release shared agent-a\n") out)
             "Taking says how to release, with the command it was reached by"))
       (is (= "x" (:claim/checkout (edn/read-string (slurp claim-file))))
           "From inside a worktree, the claim records that worktree")
       (let [{:keys [exit out]} (bbg project "claim" "take" "shared" "agent-b")]
         (is (= 1 exit) "Another agent finds it held")
         (is (str/includes? out "agent-a holds shared in x")))
       (is (= 1 (:exit (bbg project "claim" "release" "shared" "agent-b"))) "Only the holder releases it")
       (is (zero? (:exit (bbg project "claim" "release" "shared" "agent-a"))) "The holder releases it")
       (is (zero? (:exit (bbg project "claim" "take" "shared" "agent-b"))) "The next agent takes it")
       (is (= "source" (:claim/checkout (edn/read-string (slurp claim-file))))
           "From the main checkout, the claim records the source")
       (is (pos? (:exit (bbg project "claim" "take" "../x" "agent-b"))) "A resource name that would leave the state folder")
       (is (pos? (:exit (bbg project "claim" "take" "other" "agent-b" "--worktree" "../.."))) "A worktree name that would leave the worktrees folder")
       (is (pos? (:exit (bbg project "lock" "demo" "--worktree" ".state" "--" "true"))) "The state folder is no worktree to run in")))))

(deftest library-as-a-git-dep
  (in-temp-dir
   (fn [tmp]
     (let [bbg-repo (str (fs/parent (or (System/getProperty "babashka.config")
                                        (str (fs/path (fs/home) ".config" "bbg" "bb.edn")))))
           sha (str/trim (:out (p/sh {:dir bbg-repo} "git" "rev-parse" "HEAD")))
           project (project! (str tmp "/project")
                             {"worktrees.edn" (pr-str {:worktree/ports {:app {:first (free-port)}}})
                              "bb.edn" (pr-str {:deps {'bbg/worktrees {:git/url (str "file://" bbg-repo)
                                                                       :git/sha sha
                                                                       :deps/root "lib/worktrees"}}
                                                :tasks {'worktree '(bbg.worktree/exec! *command-line-args*)
                                                        'lock '(System/exit (bbg.lock/exec! *command-line-args*))
                                                        'claim '(System/exit (bbg.claim/exec! *command-line-args*))
                                                        :requires '([bbg.worktree] [bbg.lock] [bbg.claim])}})})
           bb #(select-keys (apply p/sh {:dir project :in "" :extra-env without-user-git-config} "bb" %&) [:exit :out :err])]
       (let [{:keys [exit out err]} (bb "worktree" "config")]
         (is (zero? exit) err)
         (is (str/includes? out ":ports")))
       (let [{:keys [exit out err]} (bb "worktree" "prepare" "x" "pez/x" "--new-branch")]
         (is (zero? exit) (str out err))
         (is (fs/exists? (fs/path project "worktrees" "x"))))
       (is (zero? (:exit (bb "lock" "demo" "--" "true"))) "lock from the project's own bb.edn")
       (is (zero? (:exit (bb "claim" "take" "shared" "agent-a"))) "claim from the project's own bb.edn")))))
