# What a project's worktree instructions cover

When a project gets worktrees, its agent writes the project's instructions for working in one, in the project's own words and in its AGENTS.md or a skill of its own. This is the checklist of topics those instructions cover, each with what the text should say. It comes from a project where agents work in worktrees every day, and most points exist because something went wrong without them. Topics marked (JVM), (shadow-cljs) or (dev repo) only apply to projects with that setup. The last section says what other projects leave out.

## Knowing where you are

- **Recognising a worktree session.** Say how an agent knows it works in a worktree: its session started in the worktree's folder, or the human handed it work in a named worktree. Say that a session in the main checkout leaves the worktrees alone unless it was asked to work in one.
- **Reading the main instructions from inside a worktree.** The project's instructions name the main checkout's folder, REPL sessions, watchers and commands. Give the one rule that maps them: in a worktree, read the main checkout's folder, sessions and commands as the worktree's own, and the worktree's way of getting feedback in place of the watchers. Better still, write the main instructions so they already hold from a worktree. This is the most important point, since everything else an agent reads assumes the main checkout.
- **The worktree's REPL sessions.** Name its session keys, the main checkout's session names with the worktree's name as a prefix (`<name>-clj`, `<name>-cljs`, or one for each REPL the worktree runs), and how to list what's connected. Say that a worktree agent never evaluates in the main checkout's sessions, and that every evaluation names its session key.
- **The worktree's ports and URLs.** Say where they come from, `bbg worktree port <port-name>` from inside the worktree, or with `--worktree <name>`, or the running worktrees' entries in `bbg worktree items running`, rather than giving numbers, since ports are handed out when a worktree is first prepared.
- **Committing.** Say that work is committed on the worktree's branch, in its folder, and that the main checkout's branch belongs to whoever works there.
- **Working in a worktree from the main checkout's session.** Say that such an agent uses absolute paths and `git -C <worktree folder>`, and doesn't `cd` into the worktree: in Claude Code, a `cd` within the session's folders moves its working folder for the rest of the session.
- **A worktree runs its branch's tasks.** Say that a worktree runs the project's tasks as its own branch has them, while `worktrees.edn` and the VS Code part come from the main checkout. A change to a task reaches a worktree only once it's on the worktree's branch.

## Getting feedback without the watchers

- **Tests.** The tasks running a worktree's processes can't be read by an agent, and a worktree may not run a test watcher at all. Say how tests run in a worktree: from its REPL (load each changed file, run one test while working, the full suite where the project asks for green), or as a one-shot command in the worktree's folder. Say which of these counts as the "green" the project's instructions ask for elsewhere, and that it's the full suite rather than a subset. Give how long the full suite takes, so an agent doesn't think it's stuck.
- **Compile errors and warnings.** Say where they show up when the build watch runs as a blind task: in the REPL's output, through a one-shot build command, or in the editor's problems for the worktree's files. A zero-warnings rule needs a way to see the warnings.
- **End-to-end tests.** Say which command runs them for a worktree, that it goes through `bbg lock` when the suite can only serve one checkout at a time, and that it runs in the background (see "Taking turns").

## Starting, stopping and removing

- **Starting a worktree from an agent.** Name the Joyride functions (`start-branch!` with or without a new branch, and `start-control-at!` when the project has a control), say that they return at once, and say to poll `start-status` about once a minute. Say what to do when it fails, such as running `bbg worktree prepare` in a terminal to see git's or npm's reason.
- **Leaving running worktrees alone.** Say that a worktree that already runs is used as it is. Say to ask the human before stopping or restarting one someone else may be working in, the control included.
- **Stopping.** Say that an agent stops a worktree it started for a short check, and that one it started for work it hands off keeps running and is named in the handoff.
- **Removing.** Say that removal is the human's, and that the handoff names the `bbg worktree remove <name>` command rather than spelling out git steps.
- **The cost.** Say roughly what a worktree costs (a JVM, a build watch, browser pages) and how many fit next to the main checkout, so agents don't leave worktrees running for nothing.

## Taking turns and shared data

- **Locks.** Name the commands that go through `bbg lock`, and why. For example, a test harness that kills whatever holds its port, or a build that reuses one image tag. Say to run them in the background: the lock belongs to the wrapper process, so when only the wrapper ends, for example because a foreground command timed out, the work it started may still hold the resource.
- **Claims.** Name the checks that need `bbg claim <resource> <who>` first, why (they write shared data and restore it), how to release, and what an agent does when someone else holds the claim.
- **What the checkouts share.** List what every checkout reads and writes: databases, files, a message broker, a browser profile. Say which actions write it, and that an agent asks the human before those while two refs run.
- **Two refs and the same data.** When refs can disagree on the shape of stored data, say how an agent finds out whether a record saved by one ref can be opened by the other, where the project records what it found, and that it backs data up before a save that changes its shape.
- **What a worktree leaves out.** List what a worktree doesn't run against shared resources, such as scheduled jobs, migrations, view or index writes, and subscriptions. Say that a difference in those areas comes from the setup, not the ref. Say which kinds of branch must therefore be worked on in the main checkout, for example one that adds a migration.

## Comparing two checkouts

- **Evaluating the same form in two REPLs.** Say that a namespace has to be loaded in each process before calling into it there, that every evaluation names its session, and that the agent shows the human both results.
- **Reading another ref's source.** Say where it is, the control's or a worktree's folder, so "how does main do this" is answered from there.

## When things change under you

- **The human works in parallel.** Say that the human may restart processes, reload pages or stop a worktree without telling the agent, and that an agent finds out what changed before calling something a bug.
- **After a restart.** Say what's gone after a process restart, such as helpers, scratch namespaces and mocks defined from the REPL, and that they're defined again. Say that anything taken from a running system, such as connections and clients, is stale after a system restart and is read from the system again. (JVM)
- **A process running out of memory.** Describe how it looks (evaluations hang without output, the process at several hundred percent CPU), and say that a restart is the human's. (JVM)

## Browser runtimes (shadow-cljs)

- **An agent's own runtime.** Say how an agent opens a page of its own for the worktree's cljs session, how it targets that runtime, and that it ends the page when done, since while no tab of the human's is open the human's evaluations land in the agent's page.
- **A stale cljs REPL.** Say how the stale state shows (evaluations time out after a page reload), how to confirm it, and the fix: restart the build's watch, then ask the human to reload the tab. Say that agents evaluate in a namespace of their own rather than `cljs.user`, so they don't cause it.

## Claude Code in a worktree (dev repo)

- **How the session gets its setup.** When worktrees live in a separate dev repo, say that a session started in a worktree finds the instructions in parent folders and the skills through the linked `.claude/skills`, and that the MCP servers ask for approval once, which then covers every worktree.

## What projects without a JVM or shadow-cljs leave out

A project like a Squint or Babashka one leaves out the runtime page, the stale `cljs.user` problem and the agent's own browser runtime (unless it has another way to reach a browser runtime), the JVM restart and memory points, and the system and connection staleness points. It keeps everything about knowing where you are, getting feedback, starting and stopping, taking turns and shared data. Two things get more room instead. With several REPLs per worktree, the sessions topic lists each REPL's session pair and which files route to which. With fixed ports in the project's own tasks, the ports topic says which tasks take their port from the worktree, and that an agent never starts a project task in a worktree without them.
