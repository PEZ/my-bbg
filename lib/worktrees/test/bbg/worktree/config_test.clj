(ns bbg.worktree.config-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [bbg.worktree.config :as config]
   [clojure.test :refer [deftest is testing]]))

(defn- git! [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string}
         "git" "-c" "user.email=t@example.com" "-c" "user.name=t" args))

(defn- repo!
  "Creates a git repo at `dir` with one commit holding `files`, a map of path to content."
  [dir files]
  (fs/create-dirs dir)
  (git! dir "init" "-q")
  (doseq [[path content] files]
    (spit (str (fs/path dir path)) content))
  (git! dir "add" "-A")
  (git! dir "commit" "-q" "--allow-empty" "-m" "init"))

(defn- in-temp-dir
  "Calls `f` with a new temp folder, by its canonical path, and deletes the folder after."
  [f]
  (let [dir (fs/create-temp-dir)]
    (try
      (f (str (fs/canonicalize dir)))
      (finally
        (fs/delete-tree dir)))))

(def ports "{:worktree/ports {:app {:first 8092}}}")

(deftest finding-in-project
  (in-temp-dir
   (fn [tmp]
     (let [project (str tmp "/project")]
       (repo! project {"worktrees.edn" ports})
       (git! project "worktree" "add" "-q" "worktrees/x" "-b" "pez/x")
       (fs/create-dirs (fs/path project "src" "deep"))
       (fs/create-dirs (fs/path project "worktrees" "x" "src"))
       (is (= project (config/config-dir project))
           "The project's own folder")
       (is (= project (config/config-dir (str project "/src/deep")))
           "A subfolder finds the config above it")
       (is (= project (config/config-dir (str project "/worktrees/x/src")))
           "A worktree's committed copy gives way to the main checkout's config")))))

(deftest finding-in-dev-repo
  (in-temp-dir
   (fn [root]
     (let [dev (str root "/dev")
           source (str root "/source")]
       (repo! source {"README" "x"})
       (repo! dev {"worktrees.edn" "{:worktree/source \"../source\" :worktree/ports {:app {:first 8092}}}"})
       (git! source "worktree" "add" "-q" (str dev "/worktrees/x") "-b" "pez/x")
       (fs/create-dirs (fs/path dev "worktrees" "x" "src"))
       (is (= dev (config/config-dir (str dev "/worktrees/x/src")))
           "A worktree of the source, in the dev folder, finds the dev folder's config")
       (is (= source (:worktree/source (config/config (str dev "/worktrees/x"))))
           "The source resolves against the config's folder, not the working folder")))))

(deftest finding-in-a-subfolder
  (in-temp-dir
   (fn [tmp]
     (let [project (str tmp "/project")]
       (repo! project {"README" "x"})
       (fs/create-dirs (fs/path project "app"))
       (spit (str (fs/path project "app" "worktrees.edn")) ports)
       (git! project "add" "-A")
       (git! project "commit" "-q" "-m" "config in a subfolder")
       (git! project "worktree" "add" "-q" "app/worktrees/x" "-b" "pez/x")
       (is (= (str project "/app") (config/config-dir (str project "/app/worktrees/x/app")))
           "The worktree's copy in a subfolder gives way to the same subfolder of the main checkout")))))

(deftest main-checkout-without-config
  (in-temp-dir
   (fn [tmp]
     (let [project (str tmp "/project")]
       (repo! project {"README" "x"})
       (git! project "worktree" "add" "-q" "worktrees/x" "-b" "pez/x")
       (spit (str (fs/path project "worktrees" "x" "worktrees.edn")) ports)
       (is (thrown-with-msg? clojure.lang.ExceptionInfo #"its main checkout .* has none"
                             (config/config-dir (str project "/worktrees/x")))
           "A worktree's config without one in the main checkout names both folders")))))

(deftest no-config
  (in-temp-dir
   (fn [tmp]
     (is (nil? (config/config-dir tmp))
         "A folder with no config above it")
     (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No worktrees.edn in"
                           (config/config tmp))
         "Reading where there is none says so"))))

(deftest problems
  (is (= [] (config/problems {:worktree/ports {:app {:first 8092}}}))
      "Ports are all a config needs")
  (is (= ["Unknown key :worktree/port."
          "`:worktree/ports` needs at least one port, as in `{:app {:first 8092}}`."]
         (config/problems {:worktree/port 8092}))
      "An unknown key, and no ports")
  (is (= ["The port :app needs a `:control` number, since there is a control."]
         (config/problems {:worktree/ports {:app {:first 8092}} :worktree/control {}}))
      "With a control, every port needs the control's number")
  (is (= 2 (count (config/problems {:worktree/ports {:app {:first 8092}}
                                    :worktree/prepare [[:run "npm"] [:nope]]})))
      "A step without a command vector, and a step of no known kind")
  (is (= ["`:worktree/claude-code` needs a `:dev-dir` folder, as in `{:dev-dir \".\"}`."]
         (config/problems {:worktree/ports {:app {:first 8092}} :worktree/claude-code {}}))
      "Claude Code links need the dev folder")
  (is (= [":worktree/prepare should be a vector of steps, each a vector, not [:run [\"x\"]]."]
         (config/problems {:worktree/ports {:app {:first 8092}} :worktree/prepare [:run ["x"]]}))
      "One step without the vector around the steps")
  (is (= [":worktree/dir should be a folder, not 5."]
         (config/problems {:worktree/ports {:app {:first 8092}} :worktree/dir 5}))
      "A value of the wrong type is named rather than crashing"))

(deftest resolving
  (let [resolved (config/resolve-config "/d/dev" {:worktree/source "../src"
                                                  :worktree/ports {:app {:first 8092}}
                                                  :worktree/claude-code {:dev-dir "."}})]
    (testing "Paths resolve against the config's folder"
      (is (= "/d/src" (:worktree/source resolved)))
      (is (= "/d/dev/worktrees" (:worktree/dir resolved)))
      (is (= "/d/dev/worktrees/.state" (:worktree/state-dir resolved)))
      (is (= "/d/dev" (get-in resolved [:worktree/claude-code :dev-dir]))))
    (is (= "origin" (:worktree/remote resolved))
        "Defaults fill in what the config leaves out")
    (is (= "/d/dev" (:worktree/source (config/resolve-config "/d/dev" {})))
        "The source defaults to the config's own folder")))

(deftest checkouts
  (let [resolved (config/resolve-config "/d/dev" {:worktree/source "../src"})]
    (is (= "x" (config/checkout-name resolved "/d/dev/worktrees/x/src"))
        "A folder in a worktree")
    (is (= "x" (config/checkout-name resolved "/d/dev/worktrees/x"))
        "The worktree's own folder")
    (is (nil? (config/checkout-name resolved "/d/dev/worktrees"))
        "The worktrees folder itself is no worktree")
    (is (nil? (config/checkout-name resolved "/d/dev/worktrees/.state"))
        "The state folder is no worktree")
    (is (nil? (config/checkout-name resolved "/d/dev/worktrees-old/x"))
        "A folder whose name only starts like the worktrees folder")
    (is (= "/d/dev/worktrees/x" (config/checkout-dir resolved "x"))
        "A worktree's folder")
    (is (= "/d/src" (config/checkout-dir resolved nil))
        "No name is the source checkout")))
