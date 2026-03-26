# mdq — Agent Guide

Babashka implementation of [mdq](https://github.com/yshavit/mdq) (jq for Markdown), written to the [mdq spec](https://github.com/yshavit/mdq/tree/main/tests/md_cases). Behavior should match the Rust reference unless documented otherwise in `dev/test-specs/local/`.

## Architecture

```
stdin → pre-process-front-matter → md/parse → [parse-selector → filter]* → apply-replacements → emit
```

- **Parsing**: [Instaparse](https://github.com/Engelberg/instaparse) grammar for selectors, `nextjournal.markdown` for the document AST
- **Pipeline**: `run-pipeline` reduces parsed selectors over `[nodes] → [nodes]` filter functions
- **Output**: Markdown (default), plain text, JSON (`cheshire`), or EDN

Entry point: `exec!` in `mdq.clj`.

## Key Strategies

- **Selectors mirror markdown syntax** — `# heading`, `- item`, `` ```lang ``, `[text](url)`, etc. Refer to the [mdq user manual](https://github.com/yshavit/mdq/wiki/Full-User-Manual) for the full selector reference.
- **Each selector type** has a `parse-selector` branch, a filter function, and a `selector->filter-fn` dispatch entry.
- **Text matching** (`parse-text-matcher`) returns a predicate or a `{:match-fn :replace}` map for `!s/pat/repl/`. Both are handled by `text-matches?`.
- **`collect-nodes-deep`** does depth-first pre-order extraction (`tree-seq`) — extracted nodes retain content but lose parent context.
- **Front matter** is a synthetic node injected by `exec!`, not produced by the markdown parser.
- **CLI parsing** uses `babashka.cli` (via `parse-args` wrapper). Selectors like `- foo` need a `--` separator because `babashka.cli` treats leading dashes as flags.

## Spec Compliance

Behavior matches the Rust reference except for CLI parsing differences due to `babashka.cli`:

| Difference | Workaround |
|------------|------------|
| Dash selectors (`- foo`) misparse as flags | Require `--` separator: `bbg mdq -- '- foo'` |
| Combined short flags (`-oplain`) unsupported | Space-separated only: `-o plain` |
| Flags after positionals leak into `:args` | Flags must precede positionals |

**Handling strategies in E2E tests:**

- **Local spec overrides** — `dev/test-specs/local/` files replace upstream specs with the same filename, documenting each difference in comments
- **Ignore field** — individual test cases can be skipped with `ignore: "reason"`
- **JSON comparison** — parse both expected and actual as data structures, not strings
- **Substring matching** — stderr checks use `string/includes?`, not exact match
- **Whitespace trimming** — `string/trimr` normalizes trailing newlines across execution modes

## Extending

1. **New selector**: Add branch in `parse-selector` → filter function → dispatch in `selector->filter-fn` → emit case if needed → tests
2. **New output format**: Add case in `format-output`
3. **New CLI flag**: Add branch in `parse-args`, wire in `exec!`

## Testing

- **Unit**: `bb test` — tests in `scripts/mdq/mdq_test.clj`
- **E2E**: `bb e2e-test` — runs against TOML spec files in `dev/test-specs/`
  - `--spec select_sections.toml` for a single spec
  - `--refresh` to re-download upstream specs
  - Requires **Python 3.11+** (`tomllib`) for TOML spec parsing
- **Watch tasks**: Both run via VS Code's default build task (Cmd+Shift+B)

Pattern — construct AST from markdown strings, test pure functions:
```clojure
(let [nodes (:content (md/parse "# Heading\n- item"))]
  (mdq/run-pipeline nodes "# Heading"))
```

## Gotchas

- **`System/exit` in REPL** kills the bb process — don't evaluate `exec!` in the REPL. The pipeline is factored so you can call `run-pipeline`, `parse-selector`, etc. directly. Use `process-inputs` for the closest to `exec!` without the exit risk.
- **Ordered list items** extracted by `list-filter` render as `- item` (lose ordinal context)
- **Image before link** in `parse-selector` — checks `![` before `[` since both start with brackets
- **Regex replace** returns a map, not a function — `text-matches?` dispatches on type
