(ns bbg.worktree-test
  (:require
   [bbg.worktree :as worktree]
   [bbg.worktree.config :as config]
   [clojure.test :refer [deftest is testing]]))

(deftest name-from-branch
  (is (= "loading-order" (worktree/name-of-branch "pez/loading-order"))
      "The last segment of a branch with a prefix")
  (is (= "hotfix" (worktree/name-of-branch "hotfix"))
      "A branch without a prefix is its own name"))

(deftest names-for-branches
  (is (= {:worktree/name "x"} (worktree/branch-worktree-name "pez/x" true))
      "A branch's last segment")
  (is (= {:worktree/name "new-thing"} (worktree/branch-worktree-name "new-thing" false))
      "A new branch without a prefix")
  (is (some? (:worktree/problem (worktree/branch-worktree-name "pez/control" true)))
      "A branch ending in control can't have a worktree")
  (is (some? (:worktree/problem (worktree/branch-worktree-name "pez/a&b" false)))
      "A branch whose last segment isn't a valid name")
  (is (some? (:worktree/problem (worktree/branch-worktree-name nil false)))
      "No branch"))

(deftest names-refused
  (is (some? (worktree/name-problem {:name "control" :ref "pez/control" :registry {} :control? true}))
      "A branch ending in control is refused, the name is the control's")
  (is (nil? (worktree/name-problem {:name "control" :ref "origin/main" :detach? true :registry {} :control? true}))
      "The control itself may use its name")
  (is (some? (worktree/name-problem {:name "control" :ref "origin/main" :detach? true :registry {}}))
      "A project without a control has no control to start")
  (is (some? (worktree/name-problem {:name "x" :ref "pez/x"
                                     :registry {"x" {:worktree/ref "other/x"}}}))
      "A name taken by a worktree on another branch is refused")
  (is (nil? (worktree/name-problem {:name "x" :ref "pez/x"
                                    :registry {"x" {:worktree/ref "pez/x"}}}))
      "The worktree's own branch restarts it")
  (is (some? (worktree/name-problem {:name "x" :ref "origin/main" :detach? true :registry {}}))
      "Only the control is detached, so detaching another worktree is refused")
  (is (some? (worktree/name-problem {:name "foo" :ref "pez/bar" :registry {}}))
      "A worktree's name is its branch's last segment")
  (is (some? (worktree/name-problem {:name "a&b" :ref "pez/a&b" :registry {}}))
      "A name with characters a shell would read is refused")
  (is (some? (worktree/name-problem {:name ".state" :ref "pez/.state" :registry {}}))
      "A name starting with a dot is refused, so it can't be the state folder")
  (is (re-find #"Usage" (worktree/name-problem {:name "x" :ref nil :registry {}}))
      "A missing ref gets the usage line rather than a crash"))

(deftest ports
  (let [ports {:app {:first 8092 :control 8091 :main 8090}}
        free (constantly false)]
    (is (= {:app 8091} (worktree/ports-for {:name "control" :registry {} :ports ports :in-use? free}))
        "The control keeps its number")
    (is (= {:app 8092} (worktree/ports-for {:name "a" :registry {} :ports ports :in-use? free}))
        "The first worktree gets the first number")
    (is (= {:app 8094} (worktree/ports-for {:name "c"
                                             :registry {"a" {:worktree/ports {:app 8092}}}
                                             :ports ports
                                             :in-use? #{8093}}))
        "Skips numbers other entries have and numbers something listens on")
    (is (= {:app 8095} (worktree/ports-for {:name "a"
                                             :registry {"a" {:worktree/ports {:app 8095}}}
                                             :ports ports
                                             :in-use? free}))
        "A restart keeps the worktree's number")
    (is (= {:app 8092} (worktree/ports-for {:name "a" :registry {} :ports {:app {:first 8090 :control 8090 :main 8091}} :in-use? free}))
        "The configured :control and :main numbers are never handed out")))

(deftest named-ports
  (let [ports {:squint {:first 31890} :bb {:first 31891}}
        free (constantly false)]
    (is (= {:squint 31890 :bb 31891} (worktree/ports-for {:name "a" :registry {} :ports ports :in-use? free}))
        "Each port of one worktree gets its own number, even where the ranges meet")
    (is (= {:squint 31892 :bb 31893}
           (worktree/ports-for {:name "b"
                                :registry {"a" {:worktree/ports {:squint 31890 :bb 31891}}}
                                :ports ports
                                :in-use? free}))
        "A number another worktree holds under another port name is taken")))

(deftest port-names
  (is (= {:port/name :app} (worktree/port-name-for {:app {}} nil))
      "The only port needs no name")
  (is (= {:port/name :bb} (worktree/port-name-for {:app {} :bb {}} "bb"))
      "A named port")
  (is (some? (:port/problem (worktree/port-name-for {:app {} :bb {}} nil)))
      "With several ports, one must be named")
  (is (some? (:port/problem (worktree/port-name-for {:app {}} "nope")))
      "An unknown port name"))

(deftest registry-drops-gone-folders
  (is (= {"a" {:worktree/ports {:app 8092}}}
         (worktree/drop-missing {"a" {:worktree/ports {:app 8092}}
                                 "b" {:worktree/ports {:app 8093}}}
                                #{"a"}))
      "An entry whose folder is gone, such as after git worktree remove, is dropped"))

(deftest git-args
  (is (= ["git" "-C" "/s" "worktree" "add" "--detach" "/x/control" "origin/main"]
         (worktree/worktree-args {:source "/s" :exists? false :detach? true :ref "origin/main" :path "/x/control"}))
      "A new control: a detached worktree")
  (is (= ["git" "-C" "/x/control" "checkout" "--detach" "origin/main"]
         (worktree/worktree-args {:source "/s" :exists? true :detach? true :ref "origin/main" :path "/x/control"}))
      "An existing control: a detached checkout in it")
  (is (= ["git" "-C" "/s" "worktree" "add" "-b" "pez/x" "/x/x" "abc123"]
         (worktree/worktree-args {:source "/s" :exists? false :new-branch? true :ref "pez/x" :base "abc123" :path "/x/x"}))
      "A new branch from the source's commit, in a new worktree")
  (is (= ["git" "-C" "/s" "worktree" "add" "/x/x" "pez/x"]
         (worktree/worktree-args {:source "/s" :exists? false :ref "pez/x" :path "/x/x"}))
      "An existing branch in a new worktree")
  (is (= ["git" "-C" "/x/x" "checkout" "pez/x"]
         (worktree/worktree-args {:source "/s" :exists? true :new-branch? true :ref "pez/x" :base "abc123" :path "/x/x"}))
      "A new branch whose worktree already exists is checked out rather than created again"))

(deftest removal
  (is (= [["git" "-C" "/s" "worktree" "remove" "/x/x"]
          ["git" "-C" "/s" "branch" "-D" "spike/x"]]
         (worktree/removal-args {:source "/s" :path "/x/x" :branch "spike/x"}))
      "A worktree on a branch: the worktree, then its branch")
  (is (= [["git" "-C" "/s" "worktree" "remove" "/x/control"]]
         (worktree/removal-args {:source "/s" :path "/x/control" :branch nil}))
      "The control, detached, has no branch to delete"))

(deftest sharing-a-folder
  (is (= [:keep] (worktree/share-steps {:same? true :state :link}))
      "Same file, already linked")
  (is (= [:link] (worktree/share-steps {:same? true :state :missing}))
      "Same file, nothing yet: link to the source's")
  (is (= [:install] (worktree/share-steps {:same? true :state :dir}))
      "Same file, the worktree's own folder from another file: the install replaces it")
  (is (= [:unlink :install] (worktree/share-steps {:same? false :state :link}))
      "Other file, linked: unlink before the install, so the source's own is untouched")
  (is (= [:install] (worktree/share-steps {:same? false :state :missing}))
      "Other file, nothing yet: install"))

(deftest worktree-process-by-working-folder
  (is (true? (worktree/worktree-process? "/x/worktrees/a" "/x/worktrees/a"))
      "A process working in the worktree is the worktree's")
  (is (false? (worktree/worktree-process? "/x/source" "/x/worktrees/a"))
      "The source's process on a stale port is not the worktree's")
  (is (false? (worktree/worktree-process? nil "/x/worktrees/a"))
      "A process whose folder can't be read is not the worktree's"))

(def worktree-list-porcelain
  "worktree /p/source\nHEAD aaa\nbranch refs/heads/main\n\nworktree /d/worktrees/control\nHEAD bbb\ndetached\n\nworktree /d/worktrees/x\nHEAD ccc\nbranch refs/heads/pez/x\n\nworktree /elsewhere/y\nHEAD ddd\nbranch refs/heads/pez/y\n")

(deftest worktree-list-parsing
  (is (= {"main" "/p/source" "pez/x" "/d/worktrees/x" "pez/y" "/elsewhere/y"}
         (worktree/checked-out-branches worktree-list-porcelain))
      "Each checked-out branch with its folder; a detached worktree has none")
  (is (= #{"/p/source" "/d/worktrees/control" "/d/worktrees/x" "/elsewhere/y"}
         (worktree/worktree-paths worktree-list-porcelain))
      "Every worktree's folder, the detached one included"))

(deftest worktree-picker-items
  (let [items (worktree/worktree-items {:branches ["pez/x" "main" "pez/y" "pez/z"]
                                        :checked-out {"main" "/p/source" "pez/x" "/d/worktrees/x" "pez/y" "/elsewhere/y"}
                                        :worktrees-path "/d/worktrees"
                                        :running #{"x"}})]
    (is (= ["pez/x" "pez/z"] (map :worktree/ref items))
        "Branches checked out in the source or outside the worktrees folder are left out, in the order given")
    (is (= {:worktree/ref "pez/x" :worktree/name "x" :worktree/exists? true :worktree/running? true}
           (first items))
        "A branch with a worktree of ours restarts it, marked as running")
    (is (= {:worktree/ref "pez/z" :worktree/name "z" :worktree/exists? false :worktree/running? false}
           (second items))
        "A branch without a worktree gets a new one")))

(deftest control-picker-items
  (is (= ["origin/main" "origin/pez/a" "pez/b" "main"]
         (worktree/control-refs "origin/main" "origin" ["origin/pez/a" "pez/b" "origin/HEAD" "origin" "origin/main" "main"]))
      "The default ref first, then the rest in the order given, without the remote's HEAD and the bare remote")
  (is (= ["upstream/trunk" "upstream/pez/a"]
         (worktree/control-refs "upstream/trunk" "upstream" ["upstream/pez/a" "upstream/HEAD" "upstream" "upstream/trunk"]))
      "Another remote and default ref"))

(deftest claude-settings
  (is (= {"permissions" {"additionalDirectories" ["/d"]}}
         (worktree/with-development-dir nil "/d"))
      "No settings yet: the development folder as an added directory")
  (is (= {"permissions" {"allow" ["x"] "additionalDirectories" ["/other" "/d"]}
          "model" "m"}
         (worktree/with-development-dir {"permissions" {"allow" ["x"] "additionalDirectories" ["/other"]}
                                         "model" "m"}
                                        "/d"))
      "Settings of the worktree's own are kept, and the folder is added")
  (is (= {"permissions" {"additionalDirectories" ["/d"]}}
         (worktree/with-development-dir {"permissions" {"additionalDirectories" ["/d"]}} "/d"))
      "The folder is added once"))

(deftest exclude-lines
  (testing "Derived from the config"
    (is (= ["/node_modules" "/.claude/skills" "/.claude/settings.local.json"]
           (worktree/exclude-lines (config/resolve-config "/d/dev" {:worktree/source "../src"
                                                                    :worktree/prepare [[:share "node_modules" {}] [:run ["x"]]]
                                                                    :worktree/claude-code {:dev-dir "."}})
                                   "/d/src"))
        "The dev-repo layout: shared folders and the skills link")
    (is (= ["/node_modules" "/worktrees/"]
           (worktree/exclude-lines (config/resolve-config "/p" {:worktree/prepare [[:share "node_modules" {}]]})
                                   "/p"))
        "The in-project layout: the worktrees folder too")
    (is (= ["/app/worktrees/"]
           (worktree/exclude-lines (config/resolve-config "/p/app" {}) "/p"))
        "A config in a subfolder: the worktrees folder from the repo's root"))
  (testing "Only what the file lacks is added"
    (is (= ["/.claude/skills"]
           (worktree/missing-exclude-lines "# git ls-files\n/node_modules\n" ["/node_modules" "/.claude/skills"])))
    (is (= ["/node_modules" "/.claude/skills"]
           (worktree/missing-exclude-lines nil ["/node_modules" "/.claude/skills"])))))

(deftest prepare-args
  (is (= {:name "x" :ref "pez/x" :detach? false :new-branch? true}
         (worktree/args->opts ["x" "pez/x" "--new-branch"]))
      "A new branch")
  (is (= {:name "control" :ref "origin/main" :detach? true :new-branch? false}
         (worktree/args->opts ["--detach" "control" "origin/main"]))
      "Flags before the name"))
