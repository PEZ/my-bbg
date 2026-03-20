# bbg — Global Babashka Tasks

Personal dev-environment utilities available from any directory, powered by [Babashka](https://babashka.org/).

The `bbg` wrapper (`~/bin/bbg`) runs Babashka tasks defined here, forwarding the caller's working directory via `--cwd` so tasks can operate on whatever project you're in.

## Usage

```sh
bbg <task> [args]
bbg tasks          # list available tasks
```

## Structure

- `bb.edn` — Thin task wrappers (declarative)
- `scripts/` — Task modules with implementation logic
