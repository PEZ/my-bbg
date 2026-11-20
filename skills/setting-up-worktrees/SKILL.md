---
name: setting-up-worktrees
description: "Set up git worktrees in a project, so branches run side by side, each with its own ports, processes and Calva REPL sessions, using the bbg worktree, lock and claim commands. Use when: setting up worktrees in a project, running branches in parallel, ports colliding between checkouts, adding worktrees, writing worktrees.edn, building a project's VS Code part to start and stop worktrees, making a project's tasks take their ports from a worktree, letting checkouts take turns on E2E tests or shared data, writing a project's instructions for agents working in worktrees, or bringing a project's worktree setup up to date."
---

# Setting up worktrees

This skill takes a project from no worktrees to a setup where PEZ, and agents beside him, run several branches at once. Each worktree is a git worktree of the project's source checkout, in the project's worktrees folder, named after the last segment of its branch. It runs its own processes on its own ports, and Calva connects to its REPLs under the worktree's name. A project can also have a control, one worktree detached at a ref such as the main branch, kept for comparing behaviour.

The work splits in two:

- **The shared commands** do the checkout work for every project: `worktree`, `lock` and `claim`, in a library in the bbg repo (`lib/worktrees`). They prepare a checkout, hand out ports, list worktrees for pickers, stop and remove worktrees, and let checkouts take turns on what they share.
- **The project's own parts** are what you build here: its `worktrees.edn`, its tasks taking their ports, its VS Code part that starts worktrees and connects Calva, and its instructions for agents working in a worktree.

The terms used here are in bbg's language file, `~/Projects/bbg/development/language.md` on PEZ's machine.

## Before you start

Read the project's AGENTS.md, its `bb.edn`, its build and test tasks, and its `.vscode/tasks.json`. Find out what runs when someone works on the project: watchers, REPL servers, app servers, and which ports they use. Some REPLs are served by Calva itself rather than by a process of the project, such as a Scittle REPL whose websocket server listens in the VS Code process. Those aren't a worktree's to run, so leave them out of what you suggest. Then ask PEZ about the choices that are the project's own, with what you found as context:

1. What should run for each worktree? Say what runs in the main checkout today, and suggest what a worktree needs. Agents in a worktree run tests themselves, so a test watcher is often left out.
2. Should the project have a control, and what would PEZ compare with it?
3. What do the checkouts share, such as databases, files, a message broker, a Docker image tag or a fixed test port? Which of those need taking turns?
4. Which of the project's running parts are singletons a second running system must leave alone, such as scheduled jobs, migrations or subscriptions?
5. Is the project only PEZ's, or used by others who don't have bbg? See the next section.

Ask everything you can't find out from the code in one go, and suggest an answer for each.

## Step 1: Reach the commands through bbg, or depend on the library

**Through bbg.** For a project where only PEZ uses the worktrees. The commands are `bbg worktree`, `bbg lock` and `bbg claim`, and a fix in bbg reaches the project at once. Nothing goes in the project's `bb.edn`. A public project can go this way too, as long as its tasks keep working for people without bbg: the lock wrapper in step 5 then runs the task directly when `bbg` isn't on the PATH.

**As a git dep.** For a project whose VS Code part or worktrees others use without bbg. The project's `bb.edn` depends on the bbg repo, and gets a fix when it moves to a newer commit:

```clojure
{:deps {bbg/worktrees {:git/url "https://github.com/PEZ/my-bbg"
                       :git/sha "<a commit>"
                       :deps/root "lib/worktrees"}}
 :tasks {:requires ([bbg.worktree] [bbg.lock] [bbg.claim])
         worktree {:doc "Prepare, list, stop and remove worktrees"
                   :task (bbg.worktree/exec! *command-line-args*)}
         lock {:doc "Run a command one checkout at a time"
               :task (System/exit (bbg.lock/exec! *command-line-args*))}
         claim {:doc "Take or release a claim on a shared resource"
                :task (System/exit (bbg.claim/exec! *command-line-args*))}}}
```

For `<a commit>`, use a pushed commit that has `lib/worktrees`, such as the one `git ls-remote https://github.com/PEZ/my-bbg HEAD` gives. PEZ pushes bbg, so ask him when the commit you need isn't pushed yet.

The commands are then `bb worktree`, `bb lock` and `bb claim`. The rest of this skill writes `bbg`. Read it as `bb` for a project on the git dep.

## Step 2: Write worktrees.edn

`worktrees.edn` goes in the folder the worktrees belong to: the project's root when the worktrees live inside the project (the in-project layout), or a separate dev folder next to the project (the dev-repo layout). Paths in it resolve against that folder. The commands find it from any folder of the project, and from inside a worktree they use the main checkout's.

```clojure
;; In-project layout: a project running two nREPL servers per checkout
{:worktree/ports {:squint-nrepl {:first 31890 :main 31888}
                  :bb-nrepl {:first 31670 :main 31664}}
 :worktree/prepare [[:share "node_modules" {:same "package-lock.json" :install ["npm" "ci"]}]
                    [:run ["bb" "bundle-scittle"]]]}

;; Dev-repo layout: a JVM app with a control
{:worktree/source "../app"
 :worktree/ports {:web {:first 3010 :control 3001 :main 3000}}
 :worktree/control {:default-ref "origin/main"}
 :worktree/prepare [[:share "node_modules" {:same "package-lock.json" :install ["npm" "ci"]}]
                    [:run ["npm" "run" "build:css"]]]
 :worktree/claude-code {:dev-dir "."}}
```

| Key | Default | What it says |
|---|---|---|
| `:worktree/source` | `"."` | The checkout the worktrees are made from. |
| `:worktree/dir` | `"worktrees"` | Where the worktrees live. |
| `:worktree/remote` | `"origin"` | The remote prepare fetches from. |
| `:worktree/ports` | required | Each port a worktree gets, by name: `:first`, the first number to hand out; `:control`, the control's number, needed when there is a control; `:main`, the main checkout's number, optional. |
| `:worktree/control` | absent | Present when the project has a control. Its name is always `control`. `:default-ref` leads the control picker. |
| `:worktree/prepare` | `[]` | Steps that ready a checkout once git has it, in order. `[:share <folder> {:same <file> :install <command>}]` links the source's folder while the two `<file>`s are equal, and runs the install otherwise. `[:run <command>]` runs a command in the worktree. |
| `:worktree/claude-code` | absent | Dev-repo layout only: `{:dev-dir <folder>}` links the worktree's `.claude/skills` to that folder's, and adds the folder to the worktree's `.claude/settings.local.json`, so a Claude Code session started in a worktree gets the dev repo's skills. |

Choose port ranges that don't meet the main checkout's numbers or each other's in the first few worktrees. The commands never hand out one number twice, under any port name, and skip numbers something listens on.

Check it with `bbg worktree config`, which prints the config with its defaults, or what is wrong with it.

Commit `worktrees.edn` in the in-project layout. `lock` and `claim` read it too, and refuse in a checkout with no config in or above its folder, such as a fresh clone.

All state lives in `.state` inside the worktrees folder: the registry of each worktree's ref and ports, and the lock and claim files. Prepare adds the worktrees folder, shared folders and Claude Code files to git's exclude file, so the project's `.gitignore` needs nothing.

## Step 3: Let the project's tasks take their ports

Any task a worktree runs that listens on a port has to take its port from the worktree, or two worktrees collide. Find each fixed port in `bb.edn`, `package.json`, the build config and `.vscode/tasks.json`. Two ways work, and the project can use either:

- **The VS Code part passes the port.** The task takes the port as an argument and keeps its own default for the main checkout. The VS Code part asks `bbg worktree port <port-name> --worktree <name>` and passes it on.
- **The task asks for it.** The task runs `bbg worktree port <port-name>`, which answers for the checkout holding the working folder, with the port's `:main` number in the main checkout, so this way needs `:main` set for each port. It works wherever the task runs, but makes the task depend on the commands.

The first way keeps the project's tasks working for someone without the commands. Prefer it unless PEZ says otherwise.

## Step 4: Keep a second system off the singletons

When worktrees share a database, a broker or other resources with the main checkout, a second running system must not run what only one system may run against them: scheduled jobs, migrations, view or index writes, message subscriptions, a client id that must be unique. Ask PEZ which of the project's parts those are (question 4 above), and have the worktree's start code turn them off. That code is the project's own. One way is a function the worktree's start command calls with the worktree's name and ports, which returns the system's config with those parts off. A branch that changes a singleton, such as one adding a migration, is then worked on in the main checkout, and the project's worktree instructions say so.

## Step 5: Take turns on what the checkouts share

- **A command only one checkout may run at a time,** such as an E2E suite on a fixed port or a build reusing one Docker image tag, runs through `bbg lock <lock> -- <command>`. The lock is an OS file lock, released when the process ends, so a crash leaves nothing behind. Without `--worktree`, the command runs in the checkout that holds the working folder. `lock` passes the command's exit code through, and environment variables set around the call reach the command.

  Keep the task's name by moving its body to a private task and wrapping that, so the wrapper doesn't call itself. `shell` takes the command vector through `:cmd`, not as a plain argument:

  ```clojure
  -test:e2e {:task (e2e/run! *command-line-args*)}
  test:e2e {:doc "Run the E2E tests, one checkout at a time"
            :task (System/exit (:exit (babashka.process/shell
                                       {:continue true
                                        :cmd (into ["bbg" "lock" "e2e" "--" "bb" "-test:e2e"]
                                                   *command-line-args*)})))}
  ```

  In a project reached through bbg that others use without it, the wrapper runs the task directly when `bbg` isn't on the PATH, since they have no worktrees to take turns with:

  ```clojure
  test:e2e {:doc "Run the E2E tests, one checkout at a time when bbg is there"
            :task (let [task (into ["bb" "-test:e2e"] *command-line-args*)
                        cmd (if (babashka.fs/which "bbg")
                              (into ["bbg" "lock" "e2e" "--"] task)
                              task)]
                    (System/exit (:exit (babashka.process/shell {:continue true :cmd cmd}))))}
  ```
- **A check that writes shared data and restores it** takes a claim first: `bbg claim take <resource> <who>`, then `bbg claim release <resource> <who>`. A claim goes stale after 30 minutes, or `--minutes`. `take` exits 1 and says who holds it when another agent does.

## Step 6: Build the VS Code part

The VS Code part is one Joyride namespace in the project's `.joyride/src`, and a few static VS Code tasks that call it. It does what only VS Code can: run a worktree's processes as tasks, show pickers and progress, connect Calva, and give agents functions to start worktrees with. It asks the commands for everything about checkouts: `bbg worktree name|check|prepare|stop|running|items|port`. It never reads the registry or defines the naming rule itself.

Record the bbg commit you build from in the namespace's docstring, `git -C ~/.config/bbg rev-parse --short HEAD`, so the part can be brought up to date later.

[references/vscode-part.md](references/vscode-part.md) has the pattern, from a version used every day: the start order, why messages stay out of awaited steps, the picker that takes typed values and refreshes in the background, readiness checked from outside, the agent functions that return at once, connecting Calva under the worktree's name, and the runtime page a shadow-cljs project needs to connect. Read it in full before writing the namespace. Build the namespace in the Joyride REPL, piece by piece, and try each piece as you go. The Joyride MCP tool's `awaitResult` fails on every promise for now ("No protocol method IWriter.-write"), even one resolving to a string. To see what a promise gives, `.then` it into an atom, end the evaluation on a plain value, and read the atom in a second evaluation.

What to fill in for the project: the processes a worktree runs, with their commands, environment and ports; the Calva connect sequence for each REPL, with project type, aliases, builds and after-connect code; whether a runtime page is needed and which page it opens; the waits; the picker titles; and the control's tasks when there is a control.

Processes that listen on nothing, such as a build watch, are ended by the VS Code part through their task labels. `bbg worktree stop` only ends processes listening on the worktree's ports from the worktree's folder.

## Step 7: Write the project's worktree instructions

Agents in a worktree read the project's AGENTS.md and skills, which were written for the main checkout. Write the project's instructions for working in a worktree, in its own words, in its AGENTS.md or a skill of its own. [references/worktree-instructions.md](references/worktree-instructions.md) is the checklist of what they cover: knowing which worktree you're in and which REPL sessions are yours, getting compile and test feedback where you can't read the watchers, starting and stopping worktrees from an agent, taking turns, shared data, comparing two checkouts, and what changes under a running worktree. Better still, word the main instructions so they already hold from a worktree.

## Step 8: Keep the project's tools out of the worktrees folder

In the in-project layout, the worktrees sit inside the project. Git ignores them through its exclude file, but many tools don't read that file. Check each one the project runs, and exclude the worktrees folder where it doesn't already:

- watchers and compilers (shadow-cljs, squint, tailwind and the like)
- linters and their caches (clj-kondo, clojure-lsp), and any classpath with `.` on it, such as `:paths ["." ...]` in `bb.edn`, which they index
- build contexts, such as a Docker build doing `COPY . .`: add the worktrees folder to `.dockerignore`, or every image copies every worktree
- test runners (Vitest, Jest, Playwright, a Clojure test runner's paths). Vitest's watch mode also needs `server.watch.ignored: ['**/worktrees/**']`.
- the editor: `files.watcherExclude` and `search.exclude` in the workspace settings. In a multi-root `.code-workspace`, the patterns are relative to each folder, as in `"worktrees/**"`.

## Step 9: Try it

A worktree runs the project's tasks as its own branch has them, while `worktrees.edn` and the VS Code part come from the main checkout. So commit the task changes from steps 3 and 5 first, to the branch the worktree's branch starts from. Until then the worktree's tasks ignore the ports the VS Code part passes and listen on the main checkout's fixed ports, so a start waits for REPLs that never come up on the worktree's ports.

1. `bbg worktree config` prints the config.
2. `bbg worktree prepare <name> <branch> --new-branch` prepares a worktree, and `bbg worktree port <port-name> --worktree <name>` gives its port.
3. Start the worktree from the VS Code part's task, and check that its processes run on the worktree's ports, and that Calva has the worktree's sessions while the main checkout's stay as they were.
4. From an agent, start a worktree through the Joyride function, and poll its status until it runs.
5. Stop it from its task, and `bbg worktree remove <name>` it. Remove lists what goes, and asks first only when that loses uncommitted changes or commits on no other branch or remote.
6. Run the locked task from two checkouts at once, and see them take turns.

Then give PEZ the same steps as a walkthrough to try himself.

## Bringing a setup up to date

When this skill or the commands change, a project's own parts may need to follow. The VS Code part's namespace docstring records the bbg commit it was built from, so `git -C ~/.config/bbg log <commit>.. -- skills/setting-up-worktrees lib/worktrees` lists what changed since. Bring over what changed: a renamed command or option, a new step in the start order, a new topic in the checklist. Then record the new commit in the docstring. `bbg worktree config` tells you whether the project's config still reads.

## When something here doesn't fit

The shared parts get better from each project that uses them. When you had to work something out that this skill should have told you, or a command didn't fit the project, tell PEZ what, and why, in a few lines, so the skill or the commands can change. Don't copy the commands' code into the project to work around them.

## The commands

```text
worktree prepare <name> <ref> [--new-branch] [--detach]   ; --detach is for the control
worktree check <name> <ref> [--new-branch] [--detach]     ; prints why prepare would refuse, or nothing
worktree stop <name>
worktree remove <name> [--force]                          ; lists what goes, asks when work would be lost, unless --force
worktree items worktree|control|running                   ; EDN for pickers
worktree port [<port-name>] [--worktree <name>]           ; the port name is needed when there are several
worktree name <branch>                                    ; the name a branch's worktree gets, or why none can
worktree running <name>                                   ; exits 0 when it runs, 1 when it doesn't, 2 when there's no such worktree
worktree config
lock <lock> [--worktree <name>] -- <command> [<arg>...]
claim take <resource> <who> [--worktree <name>] [--minutes 30]
claim release <resource> <who>
```

`items worktree` returns `{:current-branch "..." :items [{:worktree/ref "pez/x" :worktree/name "x" :worktree/exists? true :worktree/running? false} ...]}`, the branches in order of their last commit, leaving out branches checked out in the source or outside the worktrees folder. `items control` returns `{:items [{:worktree/ref "origin/main"} ...]}` with the default ref first. `items running` returns the running worktrees other than the control, each with its registry entry and `:worktree/name`. A worktree runs when a process in its folder listens on its `.nrepl-port` or one of its ports.
