# bbg — Global Babashka Tasks

Personal dev-environment utilities available from any directory, powered by [Babashka](https://babashka.org/).

The `bbg` wrapper runs Babashka tasks defined here, forwarding the caller's working directory via `--cwd` so tasks can operate on whatever project you're in. A symlink at `~/bin/bbg` points to the in-project `bbg` script. (Relying on that `~/bin` is in your path.)

## Usage

```sh
bbg tasks          # list available tasks
bbg <task> [args]
```

## Structure

- `bbg` — Wrapper script (symlinked from `~/bin/bbg`)
- `bb.edn` — Thin task wrappers
- `scripts/` — Task implementation modules
- `completions.zsh` — Zsh tab completions for bbg tasks
