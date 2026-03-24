# mdq Maintenance Guide

Query tool for Markdown documents. Selector DSL maps markdown syntax to filter operations, chained with `|`.

## Pipeline

```
stdin → pre-process-front-matter → md/parse → [parse-selector → filter]* → apply-replacements → emit
```

1. **Extract** front matter (YAML/TOML) into synthetic `:front-matter` node
2. **Parse** markdown to AST via `nextjournal.markdown`
3. **Filter** through chained selectors (`reduce` over `[nodes] → [nodes]` functions)
4. **Replace** text nodes if any selector had `!s/pattern/replacement/`
5. **Emit** as markdown, JSON, or EDN

Core orchestrator: `run-pipeline` chains parsed selectors via `reduce`, then calls `apply-replacements`.

## Selector Reference

| Syntax | `:type` | Filter fn | Extra keys |
|--------|---------|-----------|------------|
| `# text`, `## text` | `:section` | `section-filter` | `:level`, `:matcher` |
| `- text` | `:list-item` | `list-filter` | `:list-kind :unordered`, `:matcher` |
| `1. text` | `:list-item` | `list-filter` | `:list-kind :ordered`, `:matcher` |
| `- [ ] text` | `:task` | `task-filter` | `:task-kind :unchecked`, `:matcher` |
| `- [x] text` | `:task` | `task-filter` | `:task-kind :checked`, `:matcher` |
| `- [?] text` | `:task` | `task-filter` | `:task-kind :any`, `:matcher` |
| `> text` | `:blockquote` | `blockquote-filter` | `:matcher` |
| `` ```lang text `` | `:code` | `code-filter` | `:language-matcher`, `:matcher` |
| `P: text` | `:paragraph` | `paragraph-filter` | `:matcher` |
| `[text](url)` | `:link` | `link-filter` | `:matcher`, `:url-matcher` |
| `![alt](src)` | `:image` | `image-filter` | `:matcher`, `:url-matcher` |
| `</> text` | `:html` | `html-filter` | `:matcher` |
| `+++`, `+++yaml`, `+++toml` | `:front-matter` | `front-matter-filter` | `:format`, `:matcher` |
| `:-: col :-: row` | `:table` | `table-filter` | `:col-matcher`, `:row-matcher` |

## Text Matching

`parse-text-matcher` returns either a predicate `(fn [text] → bool)` or a map with `:match-fn` and `:replace`. Both are handled by `text-matches?`.

| Pattern | Behavior |
|---------|----------|
| `nil`, `""`, `*` | Returns `nil` (match all) |
| `hello` | Case-insensitive substring |
| `"Hello"` or `'Hello'`| Case-sensitive substring |
| `^start` | Prefix match (case-insensitive unquoted) |
| `end$` | Suffix match |
| `/regex/` | Regex |
| `!s/pat/repl/` | Regex replace — returns `{:match-fn fn, :replace {:pattern re, :replacement str}}` |

## Key AST Node Shapes

```clojure
;; Block nodes
{:type :heading :heading-level 2 :content [inline-nodes]}
{:type :paragraph :content [inline-nodes]}
{:type :bullet-list :content [list-items]}
{:type :numbered-list :attrs {:start 1} :content [list-items]}
{:type :todo-list :content [todo-items]}
{:type :list-item :content [block-nodes]}
{:type :todo-item :attrs {:checked true} :content [block-nodes]}
{:type :code :language "clojure" :content [{:type :text :text "..."}]}
{:type :blockquote :content [block-nodes]}

;; Table (nested: table → head/body → row → cell)
{:type :table :content [
  {:type :table-head :content [{:type :table-row :content [cells]}]}
  {:type :table-body :content [rows]}]}

;; Inline nodes
{:type :text :text "string"}
{:type :link :attrs {:href "url"} :content [inline-nodes]}
{:type :image :attrs {:src "url"} :content [inline-nodes]}
{:type :strong :content [inline-nodes]}
;; also: :em, :strikethrough, :monospace, :formula, :softbreak, :hardbreak

;; Synthetic (injected by exec!, not from parser)
{:type :front-matter :format :yaml :raw "title: Hello\nauthor: PEZ"}
```

## Function Map

| Function | Contract |
|----------|----------|
| `split-pipeline(s)` | `string → [selector-strings]` — splits on `\|`, respects `/regex/` and quotes |
| `parse-text-matcher(s)` | `string → (fn \| map \| nil)` |
| `text-matches?(matcher, text)` | `(fn\|map), string → bool` — dispatches on fn vs map |
| `parse-selector(s)` | `string → selector-map` |
| `selector->filter-fn(sel)` | `selector-map → (fn [nodes] → [nodes])` |
| `collect-nodes-deep(types, nodes)` | `#{types}, [nodes] → [matching-nodes]` — postwalk extraction |
| `run-pipeline(nodes, selector-str)` | `[nodes], string → [result-nodes]` |
| `apply-replacements(nodes, sels)` | `[nodes], [selectors] → [nodes]` — postwalk text replacement |
| `emit-inline(node)` | `node → string` — inline content |
| `emit-node(node)` | `node → string` — block content, calls `emit-inline` |
| `emit-markdown(nodes)` | `[nodes] → string` — joins with `\n\n` |
| `format-output(nodes, opts)` | `[nodes], {:output "markdown"\|"json"\|"edn"} → string` |
| `pre-process-front-matter(s)` | `string → {:front-matter {:format :raw} :body string}` |
| `parse-args(args)` | `[strings] → {:selector :output :quiet :help}` |
| `exec!(args)` | `[strings] → side effects` — entry point |

## Adding a New Selector

1. **Parse** — add a `cond` branch in `parse-selector`:
   ```clojure
   (str/starts-with? s "@")
   (let [text (str/trim (subs s 1))]
     {:type :mention :matcher (parse-text-matcher text)})
   ```

2. **Filter** — add function before `selector->filter-fn`:
   ```clojure
   (defn mention-filter [{:keys [matcher]} nodes]
     (let [mentions (collect-nodes-deep #{:mention} nodes)]
       (cond->> mentions
         matcher (filter #(text-matches? matcher (md/node->text %))))))
   ```

3. **Dispatch** — add case in `selector->filter-fn`:
   ```clojure
   :mention (fn [nodes] (mention-filter selector nodes))
   ```

4. **Emit** — if the node type needs custom markdown output, add a case in `emit-node`.

5. **Test** — add to `test/mdq_test.clj`:
   ```clojure
   (deftest mention-filter-test
     (let [nodes (:content (md/parse "..."))]
       (testing "filter by text"
         (is (= 1 (count (mdq/run-pipeline nodes "@alice")))))))
   ```

For selectors that restructure nodes (like tables), also implement a `rebuild-*` function.

## Other Extension Recipes

**Add output format** — add case in `format-output`:
```clojure
:yaml (yaml/generate-string {:items (nodes->data nodes)})
```

**Add CLI flag** — add branch in `parse-args`, use in `exec!`, document in help text.

## CLI Parsing

Custom `parse-args` (not `babashka.cli`) because selectors like `- foo` look like CLI flags. The parser recognizes `-o`, `-q`, `-h` as flags and treats the first unrecognized arg as the start of the selector string.

Use `--` to force selector interpretation: `bbg mdq -- '- item'`

## Gotchas

- **Ordered list items** extracted by `list-filter` render as `- item` (lose ordinal context from parent `:numbered-list`)
- **Front matter** is a synthetic node injected at position 0 of the AST by `exec!`, not by the markdown parser
- **Regex replace** (`!s/pat/repl/`) returns a map, not a function — `text-matches?` dispatches on this
- **Table `:-: *`** — wildcard matches all columns; combine with `:-: * :-: row-text` for row-only filtering
- **Image before link** — `parse-selector` checks `![` before `[` since both are bracket-prefixed
- **`collect-nodes-deep`** flattens tree structure — each extracted node retains its own content but loses parent context
- **`System/exit` in REPL** kills the process — don't evaluate `exec!` with help/quiet flags in the REPL

## Testing

Run: `bb run test` or `bb -cp "scripts:test" -e "(require 'mdq-test :reload) (clojure.test/run-tests 'mdq-test)"`

Pattern — construct AST from markdown strings, test pure functions:
```clojure
(let [nodes (:content (md/parse "# Heading\n- item"))]
  (mdq/run-pipeline nodes "# Heading"))
```

For front-matter tests, construct synthetic nodes directly since `md/parse` doesn't produce them.

## E2E Testing

Compatibility tests against the [Rust mdq](https://github.com/yshavit/mdq) reference implementation.

Run: `bbg e2e-test`

Options:
- `--refresh` — re-download spec files from GitHub
- `--spec FILE` — run a single spec (e.g., `--spec select_sections.toml`)

### How It Works

1. **Specs** — TOML test files from Rust mdq's `tests/md_cases/` directory, cached in `dev/test-specs/md_cases/`
2. **Execution** — Each test case runs `bbg mdq` as a subprocess with the spec's markdown input and CLI args
3. **Comparison** — Output, exit codes, and stderr are compared against expected values
4. **Reporting** — Per-spec PASS/FAIL summary with failure details

### Architecture

| File | Purpose |
|------|---------|
| `test/e2e_specs.clj` | Download, cache, and parse TOML specs from GitHub |
| `test/mdq_e2e_test.clj` | Test runner: execute cases, compare, report |

Key functions in `e2e-specs`: `ensure-specs!`, `load-specs`, `parse-spec`
Key functions in `mdq-e2e-test`: `run-test-case`, `run-specs`, `report-results`, `run!`

### Normalization

`normalize-expected` applies temporary output transformations to bridge known differences between our mdq and the Rust reference. Each rule is a TODO to remove as our implementation converges:

- 5-dash separator → 3-dash (separator style difference)
- Trailing whitespace trim

### Debugging Failures

```bash
# Run single spec
bbg e2e-test --spec select_sections.toml

# Inspect spec data in REPL
(require '[e2e-specs :as specs])
(def s (first (specs/load-specs :spec-file "select_sections.toml")))
(:expectations s)  ; see all test cases

# Run one test case manually
(require '[mdq-e2e-test :as e2e])
(e2e/run-test-case s (first (:expectations s)))
```

## Dependencies

All built into Babashka — no external deps required:

- `nextjournal.markdown` — markdown parser
- `cheshire.core` — JSON output
- `clj-yaml.core` — YAML front-matter (available but not actively used for parsing front matter content)
- `clojure.walk` — AST traversal (`postwalk`)
