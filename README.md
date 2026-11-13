# bbg — Global Babashka Tasks

Personal dev-environment utilities available from any directory, powered by [Babashka](https://babashka.org/).

This is also a recipe for building your own global task system with Babashka. The harness is tiny — a shell wrapper, a [bb.edn](bb.edn) with thin task definitions, and implementation modules in [scripts/](scripts/). Copy or be inspired by the pattern if it looks interesting. Delete any tasks that don't seem relevant to your workflows.

## How It Works

It's basically this, from @borkdude: https://clojureverse.org/t/help-utilizing-babashka-tasks-globally/8026/2

The `bbg` wrapper uses `bb --config ~/.config/bbg/bb.edn` so that Babashka finds the task definitions and scripts regardless of where you invoke it, while keeping your working directory intact. A symlink at `~/bin/bbg` points to the wrapper script. (Relying on `~/bin` being in your `$PATH`.)

```sh
bbg tasks          # list available tasks
bbg <task> [args]
```

### Zsh Completions

Source [completions.zsh](completions.zsh) in your `.zshrc` to get tab completion for tasks and their CLI options:

```sh
source ~/.config/bbg/completions.zsh
```

## Structure

```
bbg                  # Wrapper script (symlinked from ~/bin/bbg)
bb.edn               # Thin task wrappers → scripts/
scripts/             # Task implementation modules
  mdq/               # mdq sub-project (Markdown query tool)
completions.zsh      # Zsh tab completions
AGENTS.md            # AI agent configuration
test/                # Unit and E2E tests
dev/
  test-specs/        # TOML-based E2E test specifications
    md_cases/        # Upstream specs (cached from yshavit/mdq)
    local/           # bbg-specific specs
  docs/              # Research and design notes
```

## Tasks

The tasks here are personal utilities — they may or may not be useful to anyone else, but they demonstrate the pattern.

| Task | What it does | Notes |
|------|-------------|-------|
| `mdq` | Query/transform Markdown documents (jq for Markdown, faithful port of [mdq](https://github.com/yshavit/mdq)) | `bb` needs to be from commit `44d1c0d` or later (`bbg bb` shows status) |
| `bb` | Manage Babashka binaries — download, switch between versions | Relies on `gh` |
| `clj` | Switch between deps-clj and Homebrew Clojure installations | |
| `java` | Get help with switching beteen Java mejor versions via SDKMAN | |
| `config` | Commit and push dotfile/config repos | |
| `cursor-sync` | Export or install pinned Cursor extensions | Uses `extensions.txt` in Cursor’s User directory |
| `loc` | Count lines of code (wraps `cloc`) | |
| `bb-nrepl` | Start a Babashka nREPL server (random port) | Writes port to `./bb/.nrepl-port`; optional `--port <n>` |
| `clojure-atlas` | Build [clojure-atlas](https://github.com/Bariscanates404/clojure-atlas) graphs, and serve them all to MCP clients from one HTTP server | Needs the `clojure-atlas` CLI |

### mdq — Markdown Query Tool

The most substantial piece here. A Babashka implementation of [yshavit/mdq](https://github.com/yshavit/mdq), written to the [mdq spec](https://github.com/yshavit/mdq/tree/main/tests/md_cases). Selectors mirror the Markdown they match — `# heading`, `- list item`, `` ```language ``, `[link](url)`, etc. — and chain via pipes.

```sh
echo "# Intro\nHello\n# Details\nWorld" | bbg mdq '# Details'
# → ## Details
# → World
```

Supports text matchers (substring, `"exact"`, `/regex/`, `^anchor$`), search-and-replace (`!s/pattern/replacement/`), and output as Markdown, JSON, or EDN. See the [mdq user manual](https://github.com/yshavit/mdq/wiki/Full-User-Manual) for the full selector reference.

#### Differences from upstream mdq

Since bbg uses `babashka.cli` for argument parsing, there are a few CLI-level differences:

- **Dash-selectors need `--`**: Queries starting with `-` are misinterpreted as flags. Use `bbg mdq -- - list item` instead of `bbg mdq '- list item'`.
- **Space-separated flags**: `-o plain`, no support for `-oplain`.
- **Flags before positionals**: Flags placed after positional arguments leak into `:args`.

### bb

Meta... It makes it easy to switch to latest or some particular version of `bb`. Or to latest from `master`, or even to a version as built form a PR. Created because to make the `mdq` use Instaparse, I needed a yet to be released version of `bb`:

```sh
bbg bb master
```

And just `bbg bb` gives you status of what you are using and what is available.

### cursor-sync

Record installed extensions on the source Mac:

```sh
bbg cursor-sync --export
bbg cursor-sync --export --apply
```

`--export --apply` writes a sorted `extensions.txt` to `~/Library/Application Support/Cursor/User`, alongside settings and keybindings in the `cursor-user` repository. Each entry pins a version, such as `betterthantomorrow.calva@2.0.598`. Commit and push that file with your settings, then pull the repo on the other Mac.

Preview and apply the manifest there:

```sh
bbg cursor-sync --import
bbg cursor-sync --import --apply
```

`--import --apply` installs missing extensions and changes differing versions to match the manifest, including downgrades. Extra local extensions stay installed. Unavailable versions are reported as failures; install those from a trusted VSIX and rerun import. The task verifies the installed versions after applying the plan.

Use `--file PATH` for another manifest and `--cursor PATH` for another Cursor executable. The task handles the default Cursor installation and User directory on macOS. It does not commit, push, pull, or transfer extension settings, authentication, or enablement state.

### bb-nrepl

Afaik, `bb` does not write an `.nrepl-port` that nREPL clients can use for knowing where to connect. `bbg bb-nrepl` exists to fix that. If you are a [Calva](https://calva.io) user, by defaul, Calva will look for Babashka's port file in `<project-root>/bb/.nrepl`, so that's where this task puts it. (Albeit, if you are using Calva Jack-in to start and connect the Babashka repl, you don't need this task, Calva will figure out the port anyway.)

### clojure-atlas

[clojure-atlas](https://github.com/Bariscanates404/clojure-atlas) gives AI agents a structural map of a Clojure codebase, built with clj-kondo: callers, blast radius, map keys, tests, and more. Its MCP server speaks stdio and reads one graph. With every agent session starting its own server, that's a lot of processes (I often have 10–15 sessions open), and none of them notice when the graph is rebuilt. This task builds the graphs, and serves all of them from one HTTP server.

```sh
bbg clojure-atlas build --repo ~/Projects/my-app
bbg clojure-atlas serve
```

`build` writes the graph to `<repo>/.clojure-atlas/graph.json.gz`. Without `--repo` it builds the current directory. `serve` listens on `127.0.0.1:7979` (or `--port <n>`), and an MCP client reaches a repo's graph at `http://127.0.0.1:7979/mcp?repo=<absolute repo path>`. The first question about a repo starts one clojure-atlas process for its graph, which every session then shares. When the graph is rebuilt, the next question gets a fresh process on the new graph, and the old process finishes what it was asked before it exits. `curl -s http://127.0.0.1:7979/status` lists the graphs that have a process running.

You can run `serve` in several places at once, such as a VS Code task in each window. The first one holds the port, and the others stand by and take over when it stops.

Wiring a project is then an entry in its `.mcp.json`:

```json
"clojure-atlas": {
  "type": "http",
  "url": "http://127.0.0.1:7979/mcp?repo=/Users/you/Projects/my-app"
}
```

The `clojure-atlas` CLI isn't on npm. Install it from a clone you have read, with `npm ci`, `npm pack`, and `npm install -g ./clojure-atlas-<version>.tgz`. And add `**/.clojure-atlas/` to your global gitignore, so the graphs stay out of your repos.

## Development

There are some hidden tasks to aid in development of tasks and bbg:

| Task | What it does | Notes |
|------|-------------|-------|
| `-bbg:test:unit` | Run unit tests | `mdq`, `cursor-sync`, and `clojure-atlas` |
| `-bbg:test:e2e` | Run E2E tests against TOML spec files | Only `mdq` for now |
| `-bbg:watch:unit` | Auto-rerun unit tests on file changes | |
| `-bbg:watch:e2e` | Auto-rerun E2E tests on file changes | |

### Testing

- **Unit tests** (`bb -bbg:test:unit`) — `clojure.test` tests for mdq, cursor-sync, and clojure-atlas
- **E2E tests** (`bb -bbg:test:e2e`) — Runs mdq against TOML spec files that define input markdown, CLI args, and expected output
- **Watch tasks** — `bb -bbg:watch:unit` and `bb -bbg:watch:e2e` rerun on changes to `scripts/`, `test/`, and (for E2E) `dev/test-specs/`

E2E specs live in [dev/test-specs/](dev/test-specs/). The [md_cases/](dev/test-specs/md_cases/) directory caches upstream specs from the Rust mdq project; [local/](dev/test-specs/local/) holds bbg-specific tests. Use `bb e2e-test --refresh` to re-download upstream specs.

Run the Cursor sync tests in the connected REPL:

```clojure
(require 'cursor-sync-test :reload)
(clojure.test/run-tests 'cursor-sync-test)
```

### Agent Configuration

[AGENTS.md](AGENTS.md) configures AI agents working in this workspace to use the `babashka` and `babashka-tasks` skills, and to prefer interactive programming via the REPL. The mdq sub-project has its own [scripts/mdq/AGENTS.md](scripts/mdq/AGENTS.md) with pipeline architecture, selector reference, and function contracts.

## Sponsor my open source work ♥️

This and many other projects are provided to you open source and free to use as you wish, by Peter Strömberg a.k.a. PEZ.

* https://github.com/sponsors/PEZ

## Licence

[MIT](LICENSE)

(Free to use and open source. 🍻🗽)

## Happy coding! ❤️

With or without AI 😀
