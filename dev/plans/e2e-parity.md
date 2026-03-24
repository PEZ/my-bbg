# E2E Test Parity: Implementation Plan

## Goal

All 135 E2E test cases pass (currently 109/25/1 pass/fail/skip). The mdq Babashka Clojure tool achieves parity with Rust mdq for: footnote handling (renumbering, transitive collection, cycles, JSON output), table raw preservation and normalization, link reference modes (never-inline, keep, inline), search-replace across nested inline boundaries, text wrapping, and file argument support. Each phase leaves the system shippable with an improved or unchanged test score.

---

## Strategy: Derisk Early, Interleave Related Clusters

Start with footnotes — the hardest cluster (8 tests) — to surface unknowns from `nextjournal.markdown`'s footnote AST before committing to an emission architecture. Tables and link references follow because they share inline emission infrastructure. File arguments come last because their risk is operational (harness changes), not technical.

### Workflow Per Phase

1. **Baseline** — Note the current E2E pass count
2. **Fix** — Implement the phase's changes (REPL-first, then file edits)
3. **Test** — `bb test` (unit) + `bbg e2e-test` (E2E)
4. **Commit** — Descriptive message, clean worktree

### Test Target Progression

| Phase | Tests Fixed | Running Total |
|-------|-------------|---------------|
| 1     | 0 (infra)   | 109           |
| 2     | 1           | 110           |
| 3     | 1           | 111           |
| 4     | 1           | 112           |
| 5     | 3           | 115           |
| 6     | 1           | 116           |
| 7     | 4           | 120           |
| 8     | 1           | 121           |
| 9     | 1           | 122           |
| 10    | 2           | 124           |
| 11    | 1           | 125           |
| 12    | 1           | 126           |
| 13    | 0 (infra)   | 126           |
| 14    | 6           | 132           |
| 15    | 1           | 133           |
| 16    | 1           | 134           |
| 17    | 0 (docs)    | 134           |

---

## Phases

### Phase 1: Footnote AST Discovery & CLI Flags

Explore `nextjournal.markdown`'s footnote representation via REPL to establish the data shapes that all subsequent footnote phases depend on. Add new CLI flags to `parse-args` without implementing their behavior.

- [ ] REPL: evaluate `(md/parse "text[^1]\n\n[^1]: footnote body")` and document the exact AST shape for footnote references (inline nodes) and footnote definitions (block or metadata)
- [ ] REPL: confirm whether footnotes appear in `(:footnotes ast)`, `(:content ast)`, or both
- [ ] REPL: check if `nextjournal.markdown` preserves inline formatting inside footnote bodies (e.g., `[^1]: text with **bold**`)
- [ ] REPL: check if `nextjournal.markdown` preserves the `[^collapsed][]` collapsed footnote reference syntax vs `[^shortcut]` shortcut syntax, or normalizes them
- [ ] REPL: check how `nextjournal.markdown` represents inline links vs reference links vs collapsed/shortcut links — look for `:reference-type` or similar attrs
- [ ] Add `--renumber-footnotes` flag to `parse-args` (default: `true`, accepts `"false"` string) — stores `:renumber-footnotes` in opts
- [ ] Add `--wrap-width` flag to `parse-args` (default: `nil`, accepts integer string) — stores `:wrap-width` in opts. Support `--wrap-width=N` form
- [ ] Create a session memory file documenting discovered AST shapes for use by subsequent phases
- [ ] `bb test` passes (no regressions)
- [ ] `bbg e2e-test` score: **109**/25/1 (no change — flags parsed but not yet used)

**What the system can do now:** New CLI flags are accepted silently. Footnote and link AST shapes are documented for all subsequent phases.

---

### Phase 2: Footnote Renumbering — Default Mode

Implement footnote reference emission and definition appending with renumbering by appearance order.

**Root cause:** `emit-inline` has no case for footnote reference nodes. Footnote definitions from `(:footnotes ast)` are never emitted in markdown output.

- [ ] Add footnote-ref case to `emit-inline`: emit `[^N]` using a renumbering map from `*emit-opts*`
- [ ] In `emit-markdown`, after emitting body text, collect all footnote labels referenced (by scanning the renumbering map)
- [ ] Retrieve footnote definitions from `(:footnotes ast)` (passed via opts)
- [ ] Append footnote definitions in renumbered order: `[^1]: body text`, separated from content by a blank line
- [ ] Handle collapsed footnote ref syntax `[^label][]` — emit as plain `[^N]` after renumbering
- [ ] Pass `(:footnotes ast)` through opts to `emit-markdown` (currently only flows to JSON output in `format-output`)
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **110**/24/1 (`footnote_renumbering.toml / default` passes)
- [ ] Commit: `mdq: emit footnote references with renumbering by appearance order`

**What the system can do now:** Footnote references in emitted markdown are renumbered `[^1]`, `[^2]`, ... by appearance order, with definitions appended.

---

### Phase 3: Footnote — Without Renumbering Mode

Add support for `--renumber-footnotes false`: keep original labels, reorder definitions alphabetically.

- [ ] In `emit-inline`'s footnote-ref case, when `(:renumber-footnotes opts)` is `false`, emit the original label `[^original-label]` instead of renumbering
- [ ] In `emit-markdown`, when `renumber-footnotes` is `false`, sort footnote definitions alphabetically (numbers sort before letters, case-insensitive)
- [ ] Wire `(:renumber-footnotes opts)` from `parse-args` through `exec!` → `format-output` → `emit-markdown` → `*emit-opts*`
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **111**/23/1 (`footnote_renumbering.toml / without renumbering` passes)
- [ ] Commit: `mdq: --renumber-footnotes false keeps original labels`

**What the system can do now:** Footnotes can be emitted with original labels and alphabetically-sorted definitions.

---

### Phase 4: Table Raw Preservation

Fix pass-through table emission to preserve raw markdown structure, including extra/missing columns.

**Root cause:** `nextjournal.markdown` normalizes tables to the header column count during parsing. Extra body columns are silently dropped.

- [ ] REPL: verify that `(md/parse "| A | B |\n|---|---|\n| 1 | 2 | 3 |\n| 4 |")` drops the third column from the body row
- [ ] Implement `attach-raw-tables`: extract raw table lines from input markdown and associate them with `:table` nodes by position (following the pattern of `attach-table-alignments`)
- [ ] Attach raw table text as `:raw-table` on each `:table` node
- [ ] In `emit-node :table`, when `:raw-table` is present and the table has NOT been through a table filter (no `:normalized?` flag), emit the raw text directly
- [ ] In `rebuild-table`, assoc `:normalized? true` on rebuilt tables so raw passthrough doesn't apply
- [ ] Pass raw markdown through opts so `attach-raw-tables` has access during emission
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **112**/22/1 (`select_tables.toml / table not normalized by default` passes)
- [ ] Commit: `mdq: preserve raw table format in pass-through mode`

**What the system can do now:** Tables with irregular column counts pass through unchanged when not targeted by a `:-:` selector.

---

### Phase 5: Table Selection & Normalization

Fix table selection to normalize irregular column counts and implement row filtering with header preservation.

**Root cause:** Same as Phase 4 — extra columns are dropped by the parser. For selection, we need the full column data.

- [ ] Implement `parse-raw-table-cells`: given raw table lines, parse each row into cells by splitting on `|`. Captures all cells including extras beyond header count
- [ ] Augment `:table` nodes with `:raw-cells` (a vector of vectors of strings) alongside `:raw-table`
- [ ] In `table-filter`, use `:raw-cells` when available for full column data. Header cells define column names for col-matcher, but extra body columns are included
- [ ] When normalizing (selector applied), pad short rows with empty cells to `max-cols` across all rows
- [ ] In `rebuild-table`, create proper AST cells from raw cell strings. Set `:normalized? true`
- [ ] **`select all table cells normalizes`**: `:-: * :-:` pads to max columns
- [ ] **`select only the big red row`**: `:-: * :-: 'Big, red'` — header always survives, filter body rows
- [ ] **`chained`**: `:-: * :-: * | :-: * :-: *` — after first pass, table is normalized; second pass works on normalized result
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **115**/19/1 (3 table tests pass)
- [ ] Commit: `mdq: table selection normalizes extra/missing columns`

**What the system can do now:** Table selectors normalize ragged tables. Row filtering preserves headers. Chained table selectors work.

---

### Phase 6: Table Plain Output

Handle tables in plain text output mode with normalized column data.

- [ ] Update `node->plain-texts` for `:table` to emit each row as space-separated cell text values
- [ ] Use raw cell data when available; otherwise fall back to AST cells
- [ ] Empty cells produce no extra whitespace (e.g., "Fuzz" row with one column emits just "Fuzz")
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **116**/18/1 (`select_tables.toml / output plain` passes)
- [ ] Commit: `mdq: plain output for tables emits space-separated values`

**What the system can do now:** `-o plain` on table selections produces clean space-separated text.

---

### Phase 7: Link Reference Modes — Detection & Never-Inline

Implement `--link-format` modes starting with detection infrastructure and the default `never-inline` mode. The `never-inline` mode converts inline links to reference style while preserving collapsed/shortcut forms and non-numeric ref IDs.

**Root cause:** `nextjournal.markdown` normalizes all links to the same `:link` node shape, losing original reference style. Current emission always converts to `[text][N]`.

- [ ] Write `detect-link-forms`: cross-reference raw markdown link definitions (via `extract-ref-links`) with AST `:link` nodes to classify each as `:inline`, `:reference`, `:collapsed`, or `:shortcut`
- [ ] Extend `extract-ref-links` regex to capture single-quoted titles (`'...'`) alongside double-quoted
- [ ] Post-process the AST: walk all `:link` nodes and attach `:link-form` metadata (`:form`, `:ref-id`, `:title`, `:title-quote`)
- [ ] Map `--link-format` values: `nil` → `"never-inline"` (default behavior)
- [ ] In `emit-inline` for `:link`, when link-format is `"never-inline"`:
  - `:collapsed` → emit `[text][]`
  - `:shortcut` → emit `[text]`
  - `:reference` with non-numeric ref-id → emit `[text][ref-id]`, add ref def with original key
  - Otherwise (inline or numeric reference) → convert to sequential `[text][N]`
- [ ] In `format-ref-definitions`, support mixed key types (numeric + string). Sort numerics first, then alpha. Include title with original quote style
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **120**/14/1 (all 4 `links_references.toml` tests pass)
  - `ASSUMPTION:` `never-inline` fixes 2 tests and `keep`/`inline` fixes would also pass if the detection infrastructure handles them. If only 2 pass here, adjust the count to 117 and move the other 2 to Phase 8
- [ ] Commit: `mdq: link form detection and never-inline mode`

**What the system can do now:** Default link output preserves collapsed/shortcut links and non-numeric ref IDs while converting inline links to reference style.

---

### Phase 8: Link Format — Keep & Inline Modes

`--link-format keep` preserves each link's original form. `--link-format inline` converts all links to inline.

- [ ] **`keep` mode**: In `emit-inline :link`:
  - `:inline` → emit `[text](url)` (with title if present, using original quote style)
  - `:reference` → emit `[text][ref-id]`, register ref def
  - `:collapsed` → emit `[text][]`, register ref def with text as key
  - `:shortcut` → emit `[text]`, register ref def with text as key
  - Only emit ref definitions for non-inline links
- [ ] **`inline` mode**: In `emit-inline :link`:
  - All links → emit `[text](url)` (with title if present)
  - No reference definitions emitted
  - `ASSUMPTION:` `nextjournal.markdown` resolves ref link URLs onto `:link` node `:attrs :href` — verify in REPL
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **120**/14/1 (if Phase 7 already fixed all 4, no change; if Phase 7 fixed 2, this fixes 2 more → **119**)
  - Adjust count based on Phase 7 results
- [ ] Commit: `mdq: --link-format keep and inline modes`

**What the system can do now:** All three `--link-format` modes work: never-inline (default), keep, inline.

---

### Phase 9: Footnote Transitive Collection & Cycle Detection

When filtered results reference footnotes, collect those plus transitively referenced footnotes. Detect cycles. Also collect link definitions referenced within footnotes.

- [ ] Implement `collect-transitive-footnotes`: given a set of referenced labels and the full footnotes map, walk each footnote body's AST for footnote-ref nodes. Add new labels and recurse. Track visited labels (as a `seen` set) to break cycles
- [ ] When a footnote body contains `:link` nodes with reference-style hrefs, also collect corresponding link definitions (from `extract-ref-links`)
- [ ] In `emit-markdown`, after emitting filtered body, call `collect-transitive-footnotes` to get the complete footnote set
- [ ] Emit link definitions before footnote definitions (matching Rust mdq's ordering)
- [ ] For cyclic references: emit the footnote once, with the self-reference using the renumbered label
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **122**/12/1 (`just footnote contains footnote` + `just footnote contains link` pass)
- [ ] Commit: `mdq: transitive footnote collection with cycle detection`

**What the system can do now:** Filtered results include transitively referenced footnotes and their link definitions. Cycles handled gracefully.

---

### Phase 10: Footnote JSON Output

Implement `"footnotes"` and `"links"` sections in JSON output for filtered results with footnotes.

- [ ] In `format-output` for JSON, build the `"footnotes"` section: map of renumbered label → array of items (each footnote body through `nodes->items`)
- [ ] Build the `"links"` section: map of ref-id → `{url, title?}` for link definitions referenced within the output
- [ ] Replace the current raw `(:footnotes ast)` usage (~line 1289) with the filtered/renumbered footnote set
- [ ] Ensure footnote labels in JSON match renumbered labels (string keys "1", "2", etc.)
- [ ] Verify JSON structure: `{"items": [...], "footnotes": {"1": [...], "2": [...]}, "links": {"3a": {"url": "..."}}}`
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **124**/10/1 (`just footnote contains footnote: json` + `just footnote contains link: json` pass)
- [ ] Commit: `mdq: JSON output includes filtered/renumbered footnotes`

**What the system can do now:** JSON output faithfully represents footnotes and their link dependencies.

---

### Phase 11: Footnote Content Selectors

Enable selectors to find elements inside footnote body content.

**Test case:** `[]("/3a"$)` matches the link `[link][3a]` inside a footnote body.

- [ ] Extend the node set that filters operate on: when footnotes are present in the AST, flatten footnote content into the searchable nodes
- [ ] After filtering, if matched nodes came from footnote bodies, include relevant link definitions in output
- [ ] Expected output: the link from inside the footnote body + the definition `[3a]: https://example.com/3a`
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **125**/9/1 (`look for elements within a footnote` passes)
- [ ] Commit: `mdq: selectors search inside footnote content`

**What the system can do now:** Selectors search inside footnote bodies, finding referenced links and other elements.

---

### Phase 12: Search-Replace Across Inline Boundaries

Fix `apply-replacements` to traverse nested inline structure for text matching.

**Root cause:** `apply-replacements` uses `walk/postwalk` to find `:text` nodes, but `!s/and nested /and formerly /` needs to match across the boundary between text inside `:em` and text inside a nested `:strong`.

- [ ] REPL: parse `"- Item with _emphasis and **nested bold**_ formatting"` and inspect the AST to understand how `_emphasis and **nested bold**_` is represented
- [ ] Implement `replace-across-inlines`:
  1. Takes a parent node with `:content` (e.g., `:em`, `:paragraph`)
  2. Flattens inline tree to a concatenated string, tracking character-to-source-node mappings (each character maps to which `:text` node and offset)
  3. Applies the regex replacement on the flattened string
  4. Reconstructs the inline tree: for each original node, compute which portion of the replaced string corresponds to it, updating `:text` values and preserving wrapping nodes (`:em`, `:strong`, etc.)
  5. Handles edge cases: replacement removing all text from a node (keep wrapper with empty text), replacement inserting text (attribute to node at insertion point)
- [ ] Integrate into `apply-replacements`: when text replacements exist, walk the AST applying `replace-across-inlines` to nodes with mixed inline content
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **126**/8/1 (`search-replace nested formatting` passes)
- [ ] Commit: `mdq: search-replace traverses nested inline formatting`

**What the system can do now:** Search-replace patterns spanning `_emphasis **bold**_` boundaries produce correct output.

---

### Phase 13: E2E Harness — Support `given.files` in TOML Specs

Prerequisite for Phase 14. No user-facing change.

- [ ] In `e2e-specs/parse-spec`, extract `(:files (:given parsed))` — map of `{filename → content}`. Add as `:given-files` to the spec map
- [ ] In `mdq-e2e-test/run-test-case`, when `:given-files` is present:
  1. Create a temp directory via `babashka.fs/create-temp-dir`
  2. Write each file from `:given-files` into the temp dir
  3. Transform `cli-args`: replace filename keys with full temp paths
  4. Handle `-` stdin placeholder — still reads from stdin (the `given-md`)
  5. Clean up temp directory after test
- [ ] Update `run-mdq` to accept optional `:dir` parameter, pass to `p/shell`
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **126**/8/1 (no new passes, infrastructure only)
- [ ] Commit: `mdq: E2E harness supports given.files TOML specs`

**What the system can do now:** Test harness creates temp files for specs that need them.

---

### Phase 14: File Arguments

Support reading markdown from file arguments instead of (or in addition to) stdin.

- [ ] Modify `parse-args` to separate selector from file arguments:
  - First non-option positional arg → `:selector`
  - Subsequent non-option positional args → `:files` vector
  - `--` still forces remaining args into selector (existing behavior preserved)
- [ ] Handle `-oplain` combined short-option form: add parsing for `-o` immediately followed by value (no space). Currently `-oplain` would be treated as a selector part
- [ ] In `exec!`, when `:files` is present:
  - `"-"` → read from `*in*` (cache content; only read once for repeated `-` args)
  - Otherwise → check existence, error to stderr if missing: `entity not found while reading file "FILENAME"`, exit 1
  - Read file content via `(slurp file-path)`
  - Process each file's content through the pipeline, join results
- [ ] When `:files` is empty/nil, read from stdin (current behavior)
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **132**/2/1 (all 6 `file_args.toml` tests pass)
  - `ASSUMPTION:` First positional arg = selector, remaining = files (verify against Rust mdq)
- [ ] Commit: `mdq: support file arguments and stdin as explicit source`

**What the system can do now:** mdq reads from file arguments, supports `-` for stdin, handles missing files with proper error messages.

---

### Phase 15: Footnote — Cyclic Reference Isolation

Verify cyclic footnote reference (`[^cycle]` referencing itself) is handled correctly and the test passes.

- [ ] The `footnotes_in_footnotes.toml / cyclic reference doesn't cause infinite loop` test uses selector `- DDD | P: *` to select a paragraph referencing `[^cycle]`
- [ ] This test depends on both transitive collection (Phase 9) and the pipeline producing paragraph content from footnotes
- [ ] Verify the test passes. If not, debug and fix the specific cycle behavior:
  - The footnote should be emitted once with the self-reference preserved: `[^1]: this footnote references itself[^1]`
  - The paragraph output: `DDD: footnote contains cycle[^1]`
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **133**/1/1
- [ ] Commit: `mdq: verify cyclic footnote reference handling`

**What the system can do now:** Cyclic footnote references are handled without infinite loops.

---

### Phase 16: Text Wrapping

Implement `--wrap-width` for block-aware text wrapping.

- [ ] Write `wrap-text`: wraps at word boundaries to a given width
- [ ] In emit functions, when `:wrap-width` is set:
  - **Paragraphs**: wrap inline content at specified width
  - **Blockquotes**: wrap at `(- width 2)` for `"> "` prefix, then prefix each line with `> `
  - **List items**: wrap at `(- width indent)`, indent continuation lines to marker width
  - **Nested lists**: sublists inherit parent indentation
  - **Links/images**: treated as atomic units — never broken mid-syntax
  - **Code blocks, headings, front matter, tables**: NOT wrapped
  - **Link definitions**: emitted at end, NOT wrapped
- [ ] Handle interaction with `--link-format=keep` (the test uses both flags)
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **134**/0/1 (`wrapping at 50` passes)
- [ ] Commit: `mdq: --wrap-width for block-aware text wrapping`

**What the system can do now:** Text output wraps at a specified column width, respecting block indentation.

---

### Phase 17: Documentation

- [ ] Update `scripts/mdq/AGENTS.md`: document new flags (`--renumber-footnotes`, `--wrap-width`), footnote handling, file arguments, link format modes
- [ ] Update help text in `exec!` to include new flags
- [ ] Review `scripts/mdq/mdq-full-user-manual.md` for affected behaviors
- [ ] `bb test` passes
- [ ] `bbg e2e-test` score: **134**/0/1 (no regressions)
- [ ] Commit: `mdq: update documentation for new capabilities`

**What the system can do now:** Documentation reflects all new capabilities. The tool's help text is complete.

---

## Open Questions / Assumptions

- `ASSUMPTION:` `nextjournal.markdown` parses footnote references as a distinct node type (likely `:footnote-ref` with a `:label` key) and stores footnote definitions in `(:footnotes ast)`. Phase 1 REPL exploration resolves this before any implementation begins.

- `ASSUMPTION:` `nextjournal.markdown` does NOT preserve whether a link was originally inline, reference, collapsed, or shortcut. This must be recovered by cross-referencing with raw markdown. Phase 1 REPL exploration resolves this.

- `ASSUMPTION:` `nextjournal.markdown` normalizes tables to header column count, dropping extra body columns. Phase 4 REPL exploration confirms this.

- `ASSUMPTION:` `nextjournal.markdown` resolves reference link URLs onto the `:link` node's `:attrs :href`. If not, Phase 8 needs manual resolution from the ref-link-definitions map. Verify in REPL during Phase 1.

- `ASSUMPTION:` First positional arg in Rust mdq CLI = selector, subsequent positional args = file paths. Verify against Rust mdq source before Phase 14.

- `ASSUMPTION:` The `-oplain` combined short-option form (no space between `-o` and value) needs explicit handling in `parse-args`. Currently this would be treated as a selector part. Verify and fix in Phase 14.

- `ASSUMPTION:` Search-replace across inline boundaries (Phase 12) requires character-to-node mapping for proper redistribution. The complexity depends on whether patterns match across more than 2 adjacent `:text` nodes. Verify approach via REPL.

- `ASSUMPTION:` Wrapping treats `[text](url)` and `[text][ref]` as atomic units. The algorithm must measure link width including markdown syntax. Phase 16 REPL exploration confirms behavior.

- **DECISION:** Skip title preservation between quote style variants in link format conversions (per user instruction). Aim for correct title handling but don't block on quoting style mismatches.

- **OPEN QUESTION:** Phase 11 — does Rust mdq search footnotes by default, or only when no top-level matches are found? Clarify search scope semantics before implementing.

- **OPEN QUESTION:** Phase 12 — `replace-across-inlines` character-mapping approach vs simpler concatenate-redistribute. Evaluate feasibility of both in REPL before choosing.

---

## Design Decisions

### Table Raw Cell Strategy

Use `:raw-cells` (vector of vectors of strings) parsed from raw markdown lines, attached to `:table` nodes following the `attach-table-alignments` pattern. This data structure is reused across:
- Phase 4: raw passthrough (via `:raw-table` string)
- Phase 5: selection/normalization (via `:raw-cells` grid)
- Phase 6: plain text output

### Footnote Emission Architecture

Extend `*emit-opts*` to carry footnote state alongside existing link reference state:

```clojure
{:link-format "never-inline"           ;; existing
 :refs (atom (sorted-map))             ;; existing
 :counter (atom 0)                     ;; existing
 :url->ref (atom {})                   ;; existing
 :footnotes {...}                      ;; from (:footnotes ast) — NEW
 :renumber-footnotes true              ;; from opts — NEW
 :fn-counter (atom 0)                  ;; NEW
 :fn-label->num (atom {})}             ;; NEW
```

### Link Form Detection

Annotate AST `:link` nodes with `:link-form` metadata before emission:
- `:form` = `:inline` | `:reference` | `:collapsed` | `:shortcut`
- `:ref-id` = original ref ID (for non-numeric like `"a"`)
- `:title` + `:title-quote` = title preservation info

Detection by cross-referencing `extract-ref-links` output with link node `href` and scanning raw markdown for inline vs reference syntax.

---

## Original Plan-producing Prompt

Produce a complete implementation plan in Markdown for achieving E2E test parity in the mdq Babashka Clojure tool. Save it as `dev/plans/e2e-parity.md`.

**Context:**
- mdq is a Markdown query tool at `scripts/mdq/mdq.clj`, using `nextjournal.markdown` for parsing, with a selector DSL pipeline and multiple output formats (markdown, json, edn, plain)
- E2E test suite at `test/mdq_e2e_test.clj` runs TOML specs from Rust's mdq reference implementation
- Current state: 109 passed, 25 failed, 1 skipped out of 135 total
- The 25 failures group into 6 clusters: Footnotes (8), File Arguments (6), Tables (5), Link References (4), Wrapping (1), Search-Replace (1)

**Requirements:**
- Granular phasing (~14-17 phases), each independently shippable
- Per-phase workflow: Baseline → Fix → Test → Commit → Repeat
- All 25 failures addressed
- Full footnote parity (renumbering, transitive collection, cycle detection, JSON output)
- Skip link title preservation between quote style variants
- Include E2E harness changes for file_args support (split harness infra from tool changes)
- Data-oriented, functional Clojure — derisk early by exploring AST unknowns via REPL before implementation
- Quality gates per phase: `bb test`, `bbg e2e-test` with expected count, commit message
- Plan structure: Goal, Phases with checklists + quality gates + boundary statements, Open Questions/Assumptions, Design Decisions, Original Plan-producing Prompt

**Strategy decisions (from user):**
- Derisk hardest cluster first (footnotes → tables → links → files → wrap → search-replace)
- Pure REPL exploration phase before footnote implementation
- Full raw cell grid (`:raw-cells` vector of vectors) for table normalization
- Split link format into 3 phases (detect+never-inline, keep+inline, and keep verification separate if needed)
- Split file args into 2 phases (harness infra, tool implementation)
- Search-replace early (Phase 12) with detailed character-mapping algorithm specification

**Synthesized from:**
- Alpha plan (19 phases, detailed algorithmic specs, per-phase commit messages)
- Gamma plan (14 phases, derisk-first ordering, `:raw-cells` table strategy, REPL exploration phase)
- Cross-reviews identifying strengths and weaknesses of each approach
