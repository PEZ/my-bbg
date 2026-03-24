# Pest-Style Error Messages for mdq Parser

## Goal

mdq's parser will emit pest-style error messages with exact column positions for all 23 error test cases (19 bad_queries + 4 from other spec files). Valid queries continue parsing unchanged. Each phase leaves the system shippable.

## Scope & Effort Assessment

**23 error cases.** Estimated effort: 4–6× the JSON output fix (Fix 15). The work splits into infrastructure + five groups of errors ordered by ROI.

The parser currently has **no position tracking** and is **more lenient** than Rust mdq — many inputs that Rust rejects parse successfully in our implementation. This is the core challenge: we need to add both position awareness and stricter validation without disrupting the 86 tests that currently pass.

## Target Error Format

All errors must match this exact pest-style format:

```
Syntax error in select specifier:
 --> 1:{col}
  |
1 | {input}
  |    {pointer}
  |
  = {message}
```

Where `{pointer}` is `^---` for single position or `^---^` for a range.

## Error Cases by Group

### Group A: Dispatch Rejections (6 cases) — Quick wins

The parser's `parse-selector` catch-all already rejects these, just with wrong format. Need: position data in the error + pest formatting.

| # | Input | Col | Message |
|---|-------|-----|---------|
| 1 | `"hello"` | 1 | expected valid query |
| 14 | `~` | 1 | expected valid query |
| 16 | `2. hello` | 1 | expected valid query |
| 18 | `:-: *` | 1 | expected valid query |
| 22 | `P *` | 1 | expected valid query |
| 23 | `P : *` | 1 | expected valid query |

**Implementation**: All col 1, same message. The existing `:else` branch in `parse-selector` catches these. Just need to throw with `{:type :parse-error :col 1}` instead of plain `ex-info`.

### Group B: Trailing/Structural Validation (4 cases) — Small-medium

Parser succeeds on part of the input but doesn't validate remaining characters or specific syntax rules.

| # | Input | Col | Message |
|---|-------|-----|---------|
| 12 | `#foo` | 2 | expected end of input, space, or section options |
| 13 | `# $hello^` | 3 | expected end of input, "\*", unquoted string, regex, quoted string, or "^" |
| 19 | `# * \| :-: *` | 7 | expected end of input or selector |
| 15 | `- [*]` | 4 | expected "\[x\]", "\[x\]", or "\[?\]" |

**Implementation**: Add post-parse validation checks. Case 12: after `#`, require space or `{`. Case 13: after anchor `$`, reject `^` at end. Case 15: validate task bracket content. Case 19: requires `split-pipeline` to carry offset info so errors in later pipeline stages report correct absolute column.

### Group C: Unclosed Delimiters (4 cases) — Medium

Parser reaches end of input inside quotes, regex, or parens.

| # | Input | Col | Message |
|---|-------|-----|---------|
| 2 | `# "hello` | 9 | expected character in quoted string |
| 3 | `# 'hello` | 9 | expected character in quoted string |
| 4 | `# /hello` | 9 | expected regex character |
| 6 | `[](http` | 8 | expected "$" |

**Implementation**: Add termination checks in `parse-text-matcher`. When scanning for closing `"`, `'`, `/`, or `)`, detect end-of-input and throw with col = input length + 1 (past-end position). The col calculation needs the text-matcher's offset within the full selector string.

### Group D: Escape Sequence Validation (5 cases) — Medium-large

Requires character-by-character validation inside quoted strings, which `process-escape-sequences` currently doesn't do.

| # | Input | Col | Message |
|---|-------|-----|---------|
| 7 | `# "\x"` | 5 | expected ", ', \`, \\, n, r, or t |
| 8 | `# "\u{snowman}"` | 7 | expected 1 - 6 hex characters |
| 9 | `# "\u{}"` | 7 | expected 1 - 6 hex characters |
| 10 | `# "\u{1234567}"` | 5 | expected ", ', \`, \\, n, r, or t |
| 11 | `# "\u{FFFFFF}"` | 7–11 | invalid unicode sequence: FFFFFF |

**Implementation**: Refactor `process-escape-sequences` to validate each escape character. Must track position relative to original input. Case 10 is subtle: `\u{1234567}` has >6 hex chars, so pest rejects `\u` as invalid escape (backtracks to `\`). Case 11: valid escape syntax but invalid Unicode codepoint (range error).

### Group E: Specific Validations (4 cases) — Medium

Each needs a targeted validation check in a specific parser branch.

| # | Input | Col | Message |
|---|-------|-----|---------|
| 5 | `# /\P{/` | 4 | regex parse error: Unicode escape not closed |
| 17 | `:-: :-: row` | 5 | table column matcher cannot empty; use an explicit "\*" |
| 20 | `+++other` | 4–8 | front matter language must be "toml" or "yaml". Found "other". |
| 21 | `</> <span>` | 5 | expected end of input, "\*", unquoted string, regex, quoted string, or "^" |

**Implementation**: Case 5: wrap `re-pattern` call, catch regex exception, translate to pest error with column. Case 17: check for empty column matcher in table parsing. Case 20: validate front-matter format token. Case 21: reject `<` at start of unquoted text matcher (partially done with existing `<`-rejection, but needs pest format).

## Phases

### Phase 1: Error Infrastructure

Establish position-aware error reporting without changing parser behavior.

- [x] Add `format-pest-error` to mdq.clj (already built in REPL, just needs persisting)
- [x] Define `*selector-input*` dynamic var to thread original input through parser without changing function signatures
- [x] Create `throw-parse-error` helper: `(defn- throw-parse-error [col message & {:keys [end-col]}])`
  - Reads `*selector-input*` for the input string
  - Throws `ex-info` with `{:type :parse-error :col col :end-col end-col :message message :input *selector-input*}`
- [x] Update `exec!` catch block: detect `:parse-error` type in ex-data, format with `format-pest-error` instead of `"Error: ..."`
- [x] Bind `*selector-input*` where selectors are parsed (in `run-pipeline` or `exec!`)
- [x] Update `split-pipeline` to return `[{:text "..." :offset N}]` maps so pipeline stages know their absolute position
- [x] No new lint errors
- [x] Unit tests pass
- [x] E2E tests pass (86/135 baseline maintained)

Note: `throw-parse-error` remains public temporarily to avoid an unused-private warning until phase 2 starts calling it from parser branches.

**What the system can do now:** Error infrastructure is in place. All existing behavior unchanged. Parse errors can now carry position data to the output formatter.

---

### Phase 2: Group A — Dispatch Rejections (+6 tests)

Tighten `parse-selector` catch-all and add upfront checks for inputs that should never reach the parser.

- [x] In `parse-selector`, replace the `:else` branch's `ex-info` with `throw-parse-error` at col 1, message "expected valid query"
- [x] Add upfront validation before the `cond`: reject selectors starting with `"`, `'`, digit (unless `1.`), or `~`
- [x] Validate `P` selector requires `:` immediately after (no space, no other char) — reject `P *` and `P : *` at col 1
- [x] Validate `:-:` table selector requires at least 2 column groups — reject single `:-: *` at col 1
- [x] No new lint errors
- [x] Unit tests pass
- [x] E2E: 6 new passes (target: 92/135)

Note: the table-side phase-2 guard is intentionally narrow to reject the known invalid `:-: *` form without breaking valid single-column selectors such as `:-: Name`.

**What the system can do now:** The 6 simplest error cases produce correct pest-style messages. Parser still accepts all valid queries.

---

### Phase 3: Group B — Trailing/Structural (+4 tests)

Add post-parse validation for selector structure.

- [x] Section selector: after `#`/`##`/etc., require space or `{` — reject `#foo` at col 2
- [x] Section text matcher: validate anchor ordering — reject `$ ... ^` pattern at col of unexpected token
- [x] Task list: validate bracket content is exactly `x`, `X`, ` `, or `?` — reject `[*]` at col 4
- [x] Pipeline: use offset from `split-pipeline` to report absolute position for errors in later pipeline stages — reject invalid selector after `|` at correct col
- [x] No new lint errors
- [x] Unit tests pass
- [x] E2E: 4 new passes (target: 96/135)

**What the system can do now:** Structural validation catches malformed selectors with correct positions, including across pipeline boundaries.

---

### Phase 4: Group C — Unclosed Delimiters (+4 tests)

Detect end-of-input inside quotes, regex, and parenthesized URLs.

- [x] Add `col-offset` parameter to `parse-text-matcher` (or compute from `*selector-input*` and the text-matcher substring)
- [x] Quoted string scanning: detect missing closing quote, throw at col = end-of-input + 1
- [x] Regex scanning: detect missing closing `/`, throw at col = end-of-input + 1
- [x] Link URL: detect missing closing `)`, throw with col at end
- [x] Messages: "expected character in quoted string" for quotes, "expected regex character" for regex, `expected "$"` for link
- [x] No new lint errors
- [x] Unit tests pass
- [x] E2E: 4 new passes (target: 100/135)

**What the system can do now:** Unterminated delimiters produce clear error messages pointing to end of input.

---

### Phase 5: Group D — Escape Sequences (+5 tests)

Refactor `process-escape-sequences` for character-by-character validation with position tracking.

- [ ] Add position-tracking parameter to `process-escape-sequences`
- [ ] For each `\` escape, validate next character is one of: `"`, `'`, `` ` ``, `\`, `n`, `r`, `t`, `u` — reject unknown escapes with col at the bad char
- [ ] For `\u{...}`: validate hex chars (0-9, a-f, A-F), count 1-6 — reject non-hex or empty with col inside braces
- [ ] For `\u{1234567}` (>6 hex chars): Pest backtracks and rejects `\u` as bad escape — replicate by checking `\u` only valid when followed by `{` with 1-6 hex + `}`
- [ ] For valid hex but invalid Unicode codepoint (e.g., FFFFFF): range-check the parsed value, throw with col range spanning the hex digits
- [ ] No new lint errors
- [ ] Unit tests pass
- [ ] E2E: 5 new passes (target: 105/135)

**What the system can do now:** All escape sequence errors produce precise pest-style messages with correct columns.

---

### Phase 6: Group E — Specific Validations (+4 tests)

Targeted checks in individual parser branches.

- [ ] Regex compilation: wrap `re-pattern` in try/catch, translate Java regex exception to pest error at the problematic position
- [ ] Table parsing: detect empty column matcher (two consecutive `:-:` delimiters with no content), throw at col of second delimiter
- [ ] Front-matter format: validate token after `+++` is empty, `yaml`, or `toml` — throw with col range spanning the invalid token
- [ ] HTML text matcher: ensure the existing `<`-rejection produces pest-format error
- [ ] No new lint errors
- [ ] Unit tests pass
- [ ] E2E: 4 new passes (target: 109/135)

**What the system can do now:** All 23 error cases produce correct pest-style error messages. Parser validation is complete.

---

### Phase 7: Documentation

- [ ] Review and update developer docs affected by parser changes
- [ ] No new lint errors
- [ ] Unit tests pass
- [ ] E2E tests pass

---

## Key Design Decisions

1. **Dynamic var `*selector-input*`** — Threads original input through the parser without changing every function signature. Bound once in `exec!`/`run-pipeline`, readable anywhere in the call chain.

2. **Error data structure** — `{:type :parse-error :col N :end-col M :message "..." :input "..."}` in ex-data. The `:type` key lets `exec!` distinguish parse errors from other exceptions.

3. **`split-pipeline` returns `[{:text :offset}]`** — Each pipeline segment carries its absolute character offset, so errors in segment 2+ report correct columns relative to the full original input.

4. **Position = 1-indexed** — Matching pest's convention. Column 1 = first character.

5. **No parser rewrite** — All changes are additive validation checks. The existing `cond`-based dispatch in `parse-selector` stays. We add checks before, within, and after the existing branches.

## Open Questions / Assumptions

- `ASSUMPTION:` The 23 tests are the complete set of error-format tests. If more surface, phases extend naturally.
- `ASSUMPTION:` `process-escape-sequences` is only called from `parse-text-matcher` — verify before refactoring.
- `ASSUMPTION:` `split-pipeline` splitting is correct for all valid inputs — adding offset tracking must not break the split logic.
- `ASSUMPTION:` Column positions are 1-indexed (matching pest convention) — verified from test data.

## Original Plan-producing Prompt

Create a phased implementation plan for adding pest-style error messages to the mdq Clojure parser. The parser (in `scripts/mdq/mdq.clj`) currently has no position tracking and is more lenient than the Rust reference implementation. There are 23 error test cases (19 in `bad_queries.toml`, 4 in other spec files) that expect exact pest-format error output with column positions. A `format-pest-error` function exists in the REPL but isn't persisted. The plan should: group errors by implementation difficulty, phase work so each phase is shippable and commits independently, preserve the existing 86/135 E2E pass rate, avoid rewriting the parser (additive changes only), and estimate effort relative to the JSON output fix (~1 session). Three subagents should independently analyze the problem from different angles (parser architecture, test-driven categorization, minimal-change pragmatics), then synthesize into a single plan.
