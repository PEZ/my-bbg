# bbg — Global Babashka Tasks

Personal dev-environment utilities available from any directory, powered by [Babashka](https://babashka.org/).

This is also a recipe for building your own global task system with Babashka. The harness is tiny — a shell wrapper, a [bb.edn](bb.edn) with thin task definitions, and implementation modules in [scripts/](scripts/). Copy or be inspired by the pattern if it looks interesting. Delete any tasks that don't seem relevant to your workflows.

## How It Works

It's basically this, from @borkdude: https://clojureverse.org/t/help-utilizing-babashka-tasks-globally/8026/2

The `bbg` wrapper captures your working directory, `cd`s to `~/.config/bbg`, and runs `bb <task> --cwd <your-dir>`. Tasks get access to both the project root (for their own resources) and the caller's directory (for operating on whatever project you're in). A symlink at `~/bin/bbg` points to the wrapper script. (Relying on `~/bin` being in your `$PATH`.)

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
| `loc` | Count lines of code (wraps `cloc`) | |
| `test` | Run unit tests | Only `mdq` for now |
| `e2e-test` | Run E2E tests against TOML spec files | Onlt `mdq` for now |
| `watch-test` | Auto-rerun unit tests on file changes | |
| `watch-e2e` | Auto-rerun E2E tests on file changes | |
| `nrepl` | Start an Babashka nREPL server on a know port | Also writes the port to `bb/.nrepl-port` where Calva looks for it |

## mdq — Markdown Query Tool

The most substantial piece here. A Babashka implementation of [yshavit/mdq](https://github.com/yshavit/mdq), written to the [mdq spec](https://github.com/yshavit/mdq/tree/main/tests/md_cases). Selectors mirror the Markdown they match — `# heading`, `- list item`, `` ```language ``, `[link](url)`, etc. — and chain via pipes.

```sh
echo "# Intro\nHello\n# Details\nWorld" | bbg mdq '# Details'
# → ## Details
# → World
```

Supports text matchers (substring, `"exact"`, `/regex/`, `^anchor$`), search-and-replace (`!s/pattern/replacement/`), and output as Markdown, JSON, or EDN. See the [mdq user manual](https://github.com/yshavit/mdq/wiki/Full-User-Manual) for the full selector reference.

### Differences from upstream mdq

Since bbg uses `babashka.cli` for argument parsing, there are a few CLI-level differences:

- **Dash-selectors need `--`**: Queries starting with `-` are misinterpreted as flags. Use `bbg mdq -- - list item` instead of `bbg mdq '- list item'`.
- **Space-separated flags**: `-o plain`, no support for `-oplain`.
- **Flags before positionals**: Flags placed after positional arguments leak into `:args`.

## bb

Meta... It makes it easy to switch to latest or some particular version of `bb`. Or to latest from `master`, or even to a version as built form a PR. Created because to make the `mdq` use Instaparse, I needed a yet to be released version of `bb`:

```sh
bbg bb master
```

And just `bbg bb` gives you status of what you are using and what is available.

## Development

The project uses a VS Code multi-root workspace with `~/bin` and `~/.config/bbg`. The default build task starts an nREPL server and both test watchers.

### Testing

- **Unit tests** (`bb test`) — `clojure.test` tests for core mdq parsing and selector logic
- **E2E tests** (`bb e2e-test`) — Runs mdq against TOML spec files that define input markdown, CLI args, and expected output
- **Watch tasks** — `bb watch-test` and `bb watch-e2e` rerun on changes to `scripts/`, `test/`, and (for E2E) `dev/test-specs/`

E2E specs live in [dev/test-specs/](dev/test-specs/). The [md_cases/](dev/test-specs/md_cases/) directory caches upstream specs from the Rust mdq project; [local/](dev/test-specs/local/) holds bbg-specific tests. Use `bb e2e-test --refresh` to re-download upstream specs.

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
