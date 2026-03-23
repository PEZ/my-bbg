# Clojuredocs Surfing Expedition

Four surfers explored clojuredocs.org, following see-also links and examples starting from personally picked "hidden gem" functions. Each surfer rated 25 functions for **Powerfulness** (1–5) and **Applicability to mdq** (1–5), then multiplied them. This synthesis merges all findings: functions appearing multiple times occupy one row with combined comments.

**Scoring**: `final_score = total_product × count`, where `total_product` is the sum of each surfer's power×mdq rating and `count` is how many surfers independently discovered the function.

| Function | Count | Total Product | Final Score | Comments |
|----------|------:|-------------:|------------:|----------|
| `eduction` | 3 | 70 | 210 | Composable transducers without allocation — perfect for data pipelines; Bridges transducers to ALL collection fns without intermediate seqs |
| `halt-when` | 3 | 61 | 183 | Conditional transducer termination — stop walk on selector match; Transducer early termination — stop tree walk on first match; Transducer that stops pipeline on predicate — early exit |
| `split-with` | 3 | 52 | 156 | Split sequence at predicate boundary — section splitting; Split seq at predicate boundary — partition AST at section breaks |
| `iterate` | 3 | 52 | 156 | Infinite lazy sequences via repeated fn application — tree level generation; Infinite f(f(f(x))) sequences — state machines, tree descent |
| `reductions` | 3 | 44 | 132 | See intermediate reduce states — pipeline debugging; Shows intermediate reduce values — debug trace of fold |
| `sequence` | 3 | 44 | 132 | Lazy cached transducer application — memoized pipeline results; Cached lazy transducer application; Lazy transducer application — cached unlike eduction |
| `zipmap` | 3 | 36 | 108 | Parallel keys+vals to map — building node attribute maps; Pair keys with values — build lookup tables; Create map from parallel key/value seqs |
| `interpose` | 3 | 33 | 99 | Insert separators between elements — text/markdown joining; Insert separator between elements — AST node joining; Insert separator between elements — string building |
| `dedupe` | 3 | 30 | 90 | Remove consecutive duplicates — clean adjacent repeated nodes; Remove consecutive duplicates — clean repeated whitespace; Also works as transducer |
| `lazy-seq` | 2 | 45 | 90 | Fundamental laziness primitive — custom lazy tree traversals; Fundamental lazy construction — build custom tree walkers |
| `re-seq` | 2 | 40 | 80 | Regex to lazy sequence — markdown pattern extraction; Lazy seq of all regex matches — tokenize markdown |
| `replace` | 2 | 40 | 80 | Substitute elements via map lookup — rename AST node types; Substitute collection values via lookup map — also a transducer |
| `cat` | 2 | 40 | 80 | Transducer concatenation — flattening nested AST children; Transducer concat — flatten one level without intermediate seqs |
| `distinct` | 3 | 24 | 72 | Remove all duplicates lazily — unique selector matches; Deduplicate selectors; Also a transducer |
| `iteration` | 3 | 23 | 69 | Paginated/stepped lazy consumption — tree level walking; Paginated data consumption with continuation tokens |
| `take-while` | 2 | 32 | 64 | Conditionally consume prefix of seq — selector boundary detection; Capture content until predicate fails — grab until next heading |
| `constantly` | 3 | 21 | 63 | Constant function factory — default handlers and stubs; Returns fn that always returns x — stub/default for HOFs |
| `interleave` | 3 | 21 | 63 | Weave multiple sequences together — parallel data merging; Merge sequences element-by-element |
| `select-keys` | 2 | 28 | 56 | Key projection from maps — extract specific AST node properties; Extract subset of map by keys — pick AST node attributes |
| `run!` | 3 | 18 | 54 | Side-effecting reduce — output/emit results; Side-effects via reduce, returns nil; Clean side-effect iteration via reduce |
| `cycle` | 3 | 18 | 54 | Infinite repetition of a sequence — pattern cycling; Infinite repetition — alternating patterns in output |
| `repeatedly` | 3 | 9 | 27 | Repeated side-effecting function calls — generator patterns; Lazy seq of side-effecting fn calls — generate test data |
| `get-in` | 1 | 25 | 25 | Deep nested associative access — AST navigation essential |
| `for` | 1 | 25 | 25 | List comprehension with cartesian products, :when, :while, :let |
| `postwalk` | 1 | 25 | 25 | Bottom-up recursive tree transformation |
| `mapcat` | 1 | 25 | 25 | flatMap — map then concat, used heavily in tree ops |
| `tree-seq` | 1 | 25 | 25 | Lazy depth-first tree traversal — markdown IS a tree |
| `reduce` | 1 | 25 | 25 | THE fundamental operation — tree accumulation, building results |
| `into` | 1 | 25 | 25 | Swiss army knife of collection building with transducer support |
| `cond->` | 1 | 20 | 20 | Conditional threading, non-short-circuiting |
| `transduce` | 1 | 20 | 20 | Efficient reduce with transducer, no intermediate seqs |
| `some->` | 1 | 20 | 20 | Nil-safe threading, short-circuits on nil |
| `keep` | 1 | 20 | 20 | Map + filter nil in one step, cleaner than filter+map |
| `comp` | 1 | 20 | 20 | Function composition, transducer pipelines |
| `reduce-kv` | 1 | 20 | 20 | Map reduce with direct key-value access |
| `re-find` | 1 | 20 | 20 | First regex match — test if line matches heading pattern |
| `map-indexed` | 1 | 20 | 20 | Map with index — track positions in AST children |
| `reduced` | 1 | 16 | 16 | Early termination in reduce, essential for infinite seqs |
| `some-fn` | 1 | 16 | 16 | Try multiple predicates/extractors, first truthy wins |
| `partition-by` | 1 | 16 | 16 | Split seq when f returns new value, good for sections |
| `concat` | 1 | 16 | 16 | Lazy seq concatenation — combine AST subtrees |
| `every-pred` | 1 | 16 | 16 | Compose predicates with AND semantics, short-circuiting |
| `prewalk` | 1 | 16 | 16 | Top-down recursive tree transformation |
| `mapv` | 1 | 16 | 16 | Eager map returning vector — preserves structure for AST children |
| `group-by` | 1 | 15 | 15 | Partition collection into map by classification fn |
| `drop-while` | 1 | 12 | 12 | Skip prefix by predicate — skip past frontmatter |
| `identity` | 1 | 12 | 12 | The universal function — surprisingly useful as HOF argument |
| `not-empty` | 1 | 12 | 12 | Returns nil on empty colls, preserves type unlike seq |
| `juxt` | 1 | 12 | 12 | Parallel function application, multi-value extraction |
| `keep-indexed` | 1 | 12 | 12 | Like keep but with index, position-aware filtering |
| `as->` | 1 | 9 | 9 | Flexible threading with named binding |
| `lazy-cat` | 1 | 9 | 9 | Lazy concatenation of collections — deferred section assembly |
| `update-vals` | 1 | 9 | 9 | Transform all values in a map |
| `doseq` | 1 | 8 | 8 | for-comprehension for side effects with :when/:while/:let |
| `merge-with` | 1 | 8 | 8 | Merge maps with custom conflict resolution fn |
| `frequencies` | 1 | 8 | 8 | Count element occurrences in a collection |
| `fnil` | 1 | 6 | 6 | Nil-patching wrapper, defaults for missing values |
| `doall` | 1 | 6 | 6 | Force lazy seq realization — ensure side effects execute |
| `completing` | 1 | 6 | 6 | Add finalization step to reducing fn for transduce |
| `ensure-reduced` | 1 | 6 | 6 | Safe reduced wrapping for custom transducers |
| `repeat` | 1 | 4 | 4 | Simple value repetition — padding and filling |
| `realized?` | 1 | 2 | 2 | Inspect laziness state — debugging lazy evaluations |

## Top Insights for mdq

The **wisdom of the crowd** reveals strong convergence on three themes:

1. **Transducer ecosystem** (`eduction`, `halt-when`, `cat`, `sequence`, `completing`, `transduce`) — The transducer stack scored highest overall. `eduction` lets you compose transducer pipelines that plug into *any* collection function without intermediate allocations. `halt-when` is the "found it, stop searching" primitive that mdq's tree walking would love.

2. **Sequence splitting/boundaries** (`split-with`, `take-while`, `drop-while`, `partition-by`, `dedupe`) — Markdown has natural boundaries (headings, code fences, list levels). These functions are purpose-built for slicing at boundaries without manual index bookkeeping.

3. **Tree traversal primitives** (`tree-seq`, `postwalk`, `prewalk`, `lazy-seq`, `iterate`) — Markdown is a tree. Functions that walk, transform, and lazily generate tree structures are the backbone of any query tool.

## Methodology

- **4 surfers** (1 main + 3 subagents) each independently picked 3 starting functions and surfed clojuredocs see-also links until reaching 25 functions
- Each function was rated 1–5 for Powerfulness and 1–5 for mdq Applicability
- Functions appearing across multiple surfers accumulate higher scores via the `count × total_product` formula
- **62 unique functions** discovered across 100 total lookups
