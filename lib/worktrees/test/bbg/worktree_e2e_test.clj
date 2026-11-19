(ns bbg.worktree-e2e-test
  "Runs the worktree commands in scratch projects with real git and real processes. Slow, so it runs from its own task, not the unit test watcher."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
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
             "Restarting a worktree on its own branch is fine"))
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
             (is (zero? (:exit (bbg project "worktree" "stop" "x"))))
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
