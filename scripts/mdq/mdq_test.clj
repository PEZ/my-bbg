(ns mdq-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.string]
            [mdq]
            [nextjournal.markdown :as md]))

(deftest split-pipeline-test
  (testing "simple split"
    (is (= ["# A" "## B"] (mdq/split-pipeline "# A | ## B"))))
  (testing "single selector"
    (is (= ["# A"] (mdq/split-pipeline "# A"))))
  (testing "preserves regex pipes"
    (is (= ["# /a|b/"] (mdq/split-pipeline "# /a|b/"))))
  (testing "preserves quoted pipes"
    (is (= ["# \"a|b\""] (mdq/split-pipeline "# \"a|b\""))))
  (testing "empty input"
    (is (= [] (mdq/split-pipeline ""))))
  (testing "trims whitespace"
    (is (= ["# A" "## B"] (mdq/split-pipeline "  # A  |  ## B  ")))))

(deftest parse-text-matcher-test
  (testing "nil/empty/wildcard returns nil"
    (is (nil? (mdq/parse-text-matcher nil)))
    (is (nil? (mdq/parse-text-matcher "")))
    (is (nil? (mdq/parse-text-matcher "*"))))
  (testing "unquoted case-insensitive"
    (let [m (mdq/parse-text-matcher "hello")]
      (is (m "Hello World"))
      (is (m "HELLO"))
      (is (not (m "world")))))
  (testing "quoted case-sensitive"
    (let [m (mdq/parse-text-matcher "\"Hello\"")]
      (is (m "Hello World"))
      (is (not (m "hello world")))))
  (testing "regex"
    (let [m (mdq/parse-text-matcher "/hel+o/")]
      (is (m "hello"))
      (is (m "helllo"))
      (is (not (m "heo")))))
  (testing "anchored start"
    (let [m (mdq/parse-text-matcher "^intro")]
      (is (m "Introduction"))
      (is (not (m "the intro")))))
  (testing "anchored end"
    (let [m (mdq/parse-text-matcher "api$")]
      (is (m "REST API"))
      (is (not (m "API docs")))))
  (testing "anchored both"
    (let [m (mdq/parse-text-matcher "^hello$")]
      (is (m "HELLO"))
      (is (not (m "hello world")))))
  (testing "regex replace returns map"
    (let [m (mdq/parse-text-matcher "!s/foo/bar/")]
      (is (map? m))
      (is (mdq/text-matches? m "foo bar"))
      (is (not (mdq/text-matches? m "baz"))))))

(deftest parse-selector-test
  (testing "section selector"
    (is (= :section (:type (mdq/parse-selector "# hello"))))
    (is (= 1 (:level (mdq/parse-selector "# hello"))))
    (is (= 2 (:level (mdq/parse-selector "## hello"))))
    (is (= 3 (:level (mdq/parse-selector "###")))))
  (testing "section without text has nil matcher"
    (is (nil? (:matcher (mdq/parse-selector "#")))))
  (testing "unknown selector throws"
    (is (thrown? Exception (mdq/parse-selector "unknown")))))

(deftest slice-sections-test
  (let [nodes (:content (md/parse "# A\nfoo\n\n# B\nbar\n\n## C\nbaz"))]
    (testing "level 1 slicing"
      (let [sections (mdq/slice-sections {:level 1} nodes)]
        (is (= 2 (count sections)))
        (is (= "A" (get-in (first sections) [:heading :content 0 :text])))))
    (testing "level 2 slicing"
      (let [sections (mdq/slice-sections {:level 2} nodes)]
        (is (= 3 (count sections)))))))

(deftest section-filter-test
  (let [nodes (:content (md/parse "# Intro\nHello\n\n## Sub\nDetail\n\n# API\nEndpoints"))]
    (testing "filter by text"
      (let [result (mdq/section-filter {:level 1 :matcher (mdq/parse-text-matcher "intro")} nodes)]
        (is (= 4 (count result)))
        (is (= :heading (:type (first result))))))
    (testing "filter all sections (no matcher)"
      (let [result (mdq/section-filter {:level 1 :matcher nil} nodes)]
        (is (= 6 (count (mdq/strip-separators result))))))))

(deftest run-pipeline-test
  (let [nodes (:content (md/parse "# A\nfoo\n\n## B\nbar\n\n# C\nbaz"))]
    (testing "single selector"
      (is (= "# A\n\nfoo\n\n## B\n\nbar" (mdq/emit-markdown (mdq/run-pipeline nodes "# A")))))
    (testing "piped selectors"
      (is (= "## B\n\nbar" (mdq/emit-markdown (mdq/run-pipeline nodes "# A | ## B")))))))

(deftest emit-markdown-test
  (testing "heading roundtrip"
    (let [nodes (:content (md/parse "## Hello World"))]
      (is (= "## Hello World" (mdq/emit-markdown nodes)))))
  (testing "paragraph roundtrip"
    (let [nodes (:content (md/parse "Hello **bold** and *italic*"))]
      (is (= "Hello **bold** and *italic*" (mdq/emit-markdown nodes)))))
  (testing "code block roundtrip"
    (let [nodes (:content (md/parse "```clojure\n(+ 1 2)\n```"))]
      (is (= "```clojure\n(+ 1 2)\n\n```" (mdq/emit-markdown nodes)))))
  (testing "bullet list roundtrip"
    (let [nodes (:content (md/parse "- a\n- b\n- c"))]
      (is (= "- a\n- b\n- c" (mdq/emit-markdown nodes)))))
  (testing "ordered list roundtrip"
    (let [nodes (:content (md/parse "1. a\n2. b\n3. c"))]
      (is (= "1. a\n2. b\n3. c" (mdq/emit-markdown nodes))))))

(deftest format-output-test
  (let [nodes (:content (md/parse "# Test"))]
    (testing "markdown format"
      (is (string? (mdq/format-output nodes {:output "markdown"}))))
    (testing "json format"
      (let [out (mdq/format-output nodes {:output "json"})
            parsed (json/parse-string out true)]
        (is (vector? (:items parsed)))))
    (testing "edn format"
      (let [out (mdq/format-output nodes {:output "edn"})]
        (is (clojure.string/includes? out ":items"))))))

(deftest pre-process-front-matter-test
  (testing "YAML front matter"
    (let [result (mdq/pre-process-front-matter "---\ntitle: Test\n---\n# Hello")]
      (is (= :yaml (get-in result [:front-matter :format])))
      (is (clojure.string/includes? (:body result) "# Hello"))))
  (testing "no front matter"
    (let [result (mdq/pre-process-front-matter "# Hello")]
      (is (nil? (:front-matter result)))
      (is (= "# Hello" (:body result)))))
  (testing "TOML front matter"
    (let [result (mdq/pre-process-front-matter "+++\ntitle = \"Test\"\n+++\n# Hello")]
      (is (= :toml (get-in result [:front-matter :format]))))))

(deftest parse-selector-elements-test
  (testing "unordered list"
    (let [sel (mdq/parse-selector "- item")]
      (is (= :list-item (:type sel)))
      (is (= :unordered (:list-kind sel)))))
  (testing "ordered list"
    (let [sel (mdq/parse-selector "1. item")]
      (is (= :list-item (:type sel)))
      (is (= :ordered (:list-kind sel)))))
  (testing "unchecked task"
    (let [sel (mdq/parse-selector "- [ ] todo")]
      (is (= :task (:type sel)))
      (is (= :unchecked (:task-kind sel)))))
  (testing "checked task"
    (let [sel (mdq/parse-selector "- [x] done")]
      (is (= :task (:type sel)))
      (is (= :checked (:task-kind sel)))))
  (testing "any task"
    (is (= :any (:task-kind (mdq/parse-selector "- [?]")))))
  (testing "blockquote"
    (is (= :blockquote (:type (mdq/parse-selector "> quote")))))
  (testing "code block"
    (let [sel (mdq/parse-selector "```clojure")]
      (is (= :code (:type sel)))))
  (testing "link"
    (let [sel (mdq/parse-selector "[text](url)")]
      (is (= :link (:type sel)))))
  (testing "image"
    (let [sel (mdq/parse-selector "![alt](src)")]
      (is (= :image (:type sel)))))
  (testing "HTML"
    (is (= :html (:type (mdq/parse-selector "</> div")))))
  (testing "paragraph"
    (is (= :paragraph (:type (mdq/parse-selector "P: hello"))))))

(deftest list-filter-test
  (let [nodes (:content (md/parse "- foo\n- bar\n- baz"))]
    (testing "match by text"
      (is (= 1 (count (mdq/run-pipeline nodes "- foo")))))
    (testing "match all"
      (is (= 3 (count (mdq/strip-separators (mdq/run-pipeline nodes "-")))))))
  (let [nodes (:content (md/parse "1. Alpha\n2. Beta\n3. Gamma"))]
    (testing "ordered list match all"
      (is (= 3 (count (mdq/strip-separators (mdq/run-pipeline nodes "1."))))))
    (testing "ordered list match by text"
      (is (= 1 (count (mdq/run-pipeline nodes "1. beta")))))
    (testing "ordered list preserves numbers in output"
      (is (= "1. Alpha\n\n---\n\n2. Beta\n\n---\n\n3. Gamma"
             (mdq/emit-markdown (mdq/run-pipeline nodes "1.")))))
    (testing "ordered list filtered item preserves its number"
      (is (= "2. Beta"
             (mdq/emit-markdown (mdq/run-pipeline nodes "1. beta")))))))

(deftest task-filter-test
  (let [nodes (:content (md/parse "- [ ] todo 1\n- [x] done 1\n- [ ] todo 2"))]
    (testing "unchecked"
      (is (= 2 (count (mdq/strip-separators (mdq/run-pipeline nodes "- [ ]"))))))
    (testing "checked"
      (is (= 1 (count (mdq/run-pipeline nodes "- [x]")))))
    (testing "any"
      (is (= 3 (count (mdq/strip-separators (mdq/run-pipeline nodes "- [?]"))))))))

(deftest blockquote-filter-test
  (let [nodes (:content (md/parse "> quote one\n\n> quote two\n\nNot a quote"))]
    (testing "match all blockquotes"
      (is (= 2 (count (mdq/strip-separators (mdq/run-pipeline nodes ">")))))) 
    (testing "match by text"
      (is (= 1 (count (mdq/run-pipeline nodes "> one")))))))

(deftest code-filter-test
  (let [nodes (:content (md/parse "```clojure\n(+ 1 2)\n```\n\n```python\nprint('hi')\n```"))]
    (testing "filter by language"
      (is (= 1 (count (mdq/run-pipeline nodes "```clojure")))))
    (testing "match all code"
      (is (= 2 (count (mdq/strip-separators (mdq/run-pipeline nodes "```"))))))))

(deftest paragraph-filter-test
  (let [nodes (:content (md/parse "Hello world.\n\nGoodbye world.\n\n# Heading"))]
    (testing "match by text"
      (is (= 1 (count (mdq/run-pipeline nodes "P: hello")))))
    (testing "match all paragraphs"
      (is (= 2 (count (mdq/strip-separators (mdq/run-pipeline nodes "P:"))))))))

(deftest link-filter-test
  (let [nodes (:content (md/parse "[Google](https://google.com)\n\n[GitHub](https://github.com)"))]
    (testing "filter by URL"
      (is (= 1 (count (mdq/run-pipeline nodes "[](github)")))))
    (testing "filter by display text"
      (is (= 1 (count (mdq/run-pipeline nodes "[Google]()")))))))

(deftest image-filter-test
  (let [nodes (:content (md/parse "![Logo](logo.png)\n\n![Banner](banner.jpg)"))]
    (testing "filter by alt text"
      (is (= 1 (count (mdq/run-pipeline nodes "![Logo]()")))))
    (testing "filter by src"
      (is (= 1 (count (mdq/run-pipeline nodes "![](banner)")))))))

(deftest simple-filter-registry-test
  (let [nodes (:content (md/parse "[Google](https://google.com)\n\n[GitHub](https://github.com)\n\n```clojure\n(+ 1 2)\n```\n\n```python\nprint('hi')\n```"))]
    (testing "walk-ast traverses root then descendants in pre-order"
      (is (= [:root :paragraph :link :text :paragraph :link :text :code :text :code :text]
             (mapv :type (mdq/walk-ast nodes)))))
    (testing "shared simple-filter handles link text and url matchers"
      (let [simple-filter #'mdq/simple-filter]
        (is (= 1 (count (simple-filter (mdq/parse-selector "[](github)") nodes)))
            "URL matcher should narrow link matches")
        (is (= 1 (count (simple-filter (mdq/parse-selector "[Google]()") nodes)))
            "Text matcher should narrow link matches")))
    (testing "shared simple-filter handles code language and body matchers"
      (let [simple-filter #'mdq/simple-filter]
        (is (= 1 (count (simple-filter (mdq/parse-selector "```clojure") nodes)))
            "Language matcher should narrow code matches")
        (is (= 1 (count (simple-filter (mdq/parse-selector "``` * (+ 1 2)") nodes)))
            "Body matcher should narrow code matches")))))

(deftest parse-args-test
  (testing "basic selector"
    (is (= "# hello" (:selector (mdq/parse-args ["# hello"])))))
  (testing "selector with flags"
    (let [opts (mdq/parse-args ["-o" "json" "# hello"])]
      (is (= "json" (:output opts)))
      (is (= "# hello" (:selector opts)))))
  (testing "selector starting with dash"
    (is (= "- foo" (:selector (mdq/parse-args ["- foo"])))))
  (testing "quiet flag"
    (is (true? (:quiet (mdq/parse-args ["-q" "# test"])))))
  (testing "double-dash separator"
    (is (= "- foo" (:selector (mdq/parse-args ["--" "- foo"]))))))

(deftest piped-element-selectors-test
  (let [nodes (:content (md/parse "# Setup\n- item 1\n- item 2\n\n# API\n- endpoint 1\n"))]
    (testing "section then list"
      (is (= 2 (count (mdq/strip-separators (mdq/run-pipeline nodes "# Setup | -"))))))))

(deftest front-matter-filter-test
  (testing "filter YAML front matter"
    (let [fm-node {:type :front-matter :format :yaml :raw "title: Hello\nauthor: PEZ"}
          other-nodes (:content (md/parse "# Heading\nContent"))
          all-nodes (cons fm-node other-nodes)]
      (testing "match any front matter with +++"
        (is (= 1 (count (mdq/run-pipeline all-nodes "+++")))))
      (testing "match yaml format specifically"
        (is (= 1 (count (mdq/run-pipeline all-nodes "+++yaml")))))
      (testing "no match for toml format"
        (is (empty? (mdq/run-pipeline all-nodes "+++toml"))))
      (testing "match by content text"
        (is (= 1 (count (mdq/run-pipeline all-nodes "+++yaml PEZ")))))
      (testing "no match when content doesn't match"
        (is (empty? (mdq/run-pipeline all-nodes "+++yaml nonexistent"))))))
  (testing "filter TOML front matter"
    (let [fm-node {:type :front-matter :format :toml :raw "title = \"Test\"\ndate = 2024"}
          other-nodes (:content (md/parse "# Content"))
          all-nodes (cons fm-node other-nodes)]
      (testing "match toml format"
        (is (= 1 (count (mdq/run-pipeline all-nodes "+++toml")))))
      (testing "no match for yaml"
        (is (empty? (mdq/run-pipeline all-nodes "+++yaml"))))
      (testing "match toml with content"
        (is (= 1 (count (mdq/run-pipeline all-nodes "+++toml Test")))))))
  (testing "output front matter in markdown"
    (let [fm-node {:type :front-matter :format :yaml :raw "title: Doc"}
          result (mdq/emit-markdown [fm-node])]
      (is (clojure.string/includes? result "---"))
      (is (clojure.string/includes? result "title: Doc")))))

(deftest table-filter-test
  (let [table-md "| Name | Age | City |\n|------|-----|------|\n| Alice | 30 | NYC |\n| Bob | 25 | LA |\n| Charlie | 35 | SF |"
        nodes (:content (md/parse table-md))]
    (testing "filter single column by name"
      (let [result (mdq/run-pipeline nodes ":-: Name")
            output (mdq/emit-markdown result)]
        (is (= 1 (count result)))
        (is (clojure.string/includes? output "Name"))
        (is (not (clojure.string/includes? output "Age")))
        (is (not (clojure.string/includes? output "City")))))
    (testing "filter multiple columns with wildcard, then filter rows"
      (let [result (mdq/run-pipeline nodes ":-: * :-: Alice")
            output (mdq/emit-markdown result)]
        (is (= 1 (count result)))
        (is (clojure.string/includes? output "Alice"))
        (is (not (clojure.string/includes? output "Bob")))
        (is (not (clojure.string/includes? output "Charlie")))))
    (testing "filter specific column and specific row"
      (let [result (mdq/run-pipeline nodes ":-: Name :-: Alice")
            output (mdq/emit-markdown result)]
        (is (clojure.string/includes? output "Alice"))
        (is (not (clojure.string/includes? output "Age")))
        (is (not (clojure.string/includes? output "Bob")))))
    (testing "filter rows by city"
      (let [result (mdq/run-pipeline nodes ":-: * :-: NYC")
            output (mdq/emit-markdown result)]
        (is (clojure.string/includes? output "Alice"))
        (is (clojure.string/includes? output "NYC"))
        (is (not (clojure.string/includes? output "Bob")))))
    (testing "filter columns case-insensitive"
      (let [result (mdq/run-pipeline nodes ":-: name")
            output (mdq/emit-markdown result)]
        (is (clojure.string/includes? output "Name"))
        (is (not (clojure.string/includes? output "Age")))))))

(deftest regex-replace-test
  (let [nodes (:content (md/parse "Hello world. Goodbye world."))]
    (testing "simple regex replacement"
      (let [result (mdq/run-pipeline nodes "P: !s/world/earth/")
            output (mdq/emit-markdown result)]
        (is (clojure.string/includes? output "earth"))
        (is (not (clojure.string/includes? output "world")))))
    (testing "regex replacement preserves structure"
      (let [result (mdq/run-pipeline nodes "P: !s/world/earth/")]
        (is (= 1 (count result)))
        (is (= :paragraph (:type (first result)))))))
  (testing "replacement in headings"
    (let [nodes (:content (md/parse "# Hello world\n\n## Another world"))]
      (testing "replace text in all headings"
        (let [result (mdq/run-pipeline nodes "# !s/world/universe/")
              output (mdq/emit-markdown result)]
          (is (clojure.string/includes? output "universe"))
          (is (not (clojure.string/includes? output "world")))))))
  (testing "replacement with regex patterns"
    (let [nodes (:content (md/parse "Version 1.2.3 and version 4.5.6"))]
      (testing "replace digit patterns"
        (let [result (mdq/run-pipeline nodes "P: !s/\\d+\\.\\d+\\.\\d+/X.Y.Z/")
              output (mdq/emit-markdown result)]
          (is (clojure.string/includes? output "X.Y.Z"))))))
  (testing "replacement in code blocks"
    (let [nodes (:content (md/parse "```clojure\n(def foo 42)\n```"))]
      (testing "replace in code"
        (let [result (mdq/run-pipeline nodes "``` * !s/foo/bar/")
              output (mdq/emit-markdown result)]
          (is (clojure.string/includes? output "bar"))
          (is (not (clojure.string/includes? output "foo"))))))))

(deftest anchored-quoted-text-matcher-test
  (testing "anchored quoted start"
    (let [m (mdq/parse-text-matcher "^\"Hello\"")]
      (is (m "Hello World"))
      (is (not (m "Say Hello")))))
  (testing "anchored quoted end"
    (let [m (mdq/parse-text-matcher "\"World\"$")]
      (is (m "Hello World"))
      (is (not (m "World Tour")))))
  (testing "anchored quoted both"
    (let [m (mdq/parse-text-matcher "^\"Hello\"$")]
      (is (m "Hello"))
      (is (not (m "Hello World"))))))

(deftest escape-sequences-test
  (testing "escape sequences in quoted strings"
    (let [m (mdq/parse-text-matcher "\"hello\\nworld\"")]
      (is (m "hello\nworld"))
      (is (not (m "hello\\nworld"))))
    (let [m (mdq/parse-text-matcher "\"it\\'s\"")]
      (is (m "it's")))
    (let [m (mdq/parse-text-matcher "\"back\\\\slash\"")]
      (is (m "back\\slash")))
    (let [m (mdq/parse-text-matcher "\"snow\\u{2603}man\"")]
      (is (m "snow☃man")))))

(deftest section-level-range-test
  (testing "section level range selectors"
    (let [{:keys [level-range]} (mdq/parse-selector "#{2}")]
      (is (= [2 2] level-range)))
    (let [{:keys [level-range]} (mdq/parse-selector "#{2,4}")]
      (is (= [2 4] level-range)))
    (let [{:keys [level-range]} (mdq/parse-selector "#{2,}")]
      (is (= [2 6] level-range)))
    (let [{:keys [level-range]} (mdq/parse-selector "#{,3}")]
      (is (= [1 3] level-range))))
  (testing "section level range filtering"
    (let [nodes (:content (md/parse "# H1\nA\n## H2\nB\n### H3\nC\n#### H4\nD"))]
      (is (= 2 (count (mdq/slice-sections {:level-range [2 3]} nodes))))
      (is (= "## H2\n\nB\n\n---\n\n### H3\n\nC\n\n#### H4\n\nD"
             (mdq/emit-markdown (mdq/run-pipeline nodes "#{2,3}")))))))

(deftest ordered-task-selector-test
  (testing "ordered task selectors"
    (let [{:keys [type task-kind list-kind]} (mdq/parse-selector "1. [x] done")]
      (is (= :task type))
      (is (= :checked task-kind))
      (is (= :ordered list-kind)))
    (let [{:keys [type task-kind list-kind]} (mdq/parse-selector "1. [ ]")]
      (is (= :task type))
      (is (= :unchecked task-kind))
      (is (= :ordered list-kind)))))

(deftest html-inline-test
  (testing "html inline nodes"
    (let [nodes (:content (md/parse "Some <span>inline</span> here."))]
      (is (pos? (count (mdq/run-pipeline nodes "</> span")))))))

(deftest whitespace-trimming-link-selector-test
  (testing "whitespace trimming in link selector"
    (let [nodes (:content (md/parse "[foo](https://example.com)"))]
      (is (pos? (count (mdq/run-pipeline nodes "[](  https://example.com  )"))))))
  (testing "whitespace trimming in image selector"
    (let [nodes (:content (md/parse "![alt](https://img.com/pic.png)"))]
      (is (pos? (count (mdq/run-pipeline nodes "![](  https://img.com/pic.png  )")))))))

(deftest nodes-items-test
  (testing "paragraph item"
    (let [{:keys [paragraph]} (first (mdq/nodes->items
                                       (:content (md/parse "Hello **bold**"))))]
      (is (= "Hello **bold**" paragraph))))

  (testing "code block item"
    (let [{:keys [code-block]} (first (mdq/nodes->items
                                        (:content (md/parse "```rust metadata\nfn main() {}\n```"))))]
      (is (= "fn main() {}\n" (:code code-block)))
      (is (= "rust" (:language code-block)))
      (is (= "metadata" (:metadata code-block)))))

  (testing "code block without metadata"
    (let [{:keys [code-block]} (first (mdq/nodes->items
                                        (:content (md/parse "```python\nprint('hi')\n```"))))]
      (is (= "python" (:language code-block)))
      (is (nil? (:metadata code-block)))))

  (testing "link item"
    (let [nodes (mdq/run-pipeline
                  (:content (md/parse "[Google](https://google.com)"))
                  "[]()")
          {:keys [link]} (first (mdq/nodes->items nodes))]
      (is (= "https://google.com" (:url link)))
      (is (= "Google" (:display link)))))

  (testing "list item with index"
    (let [{:keys [list]} (first (mdq/nodes->items
                                  (:content (md/parse "1. one\n2. two"))))]
      (is (= 2 (count list)))
      (is (= 1 (:index (first list))))))

  (testing "thematic break item"
    (let [item (first (mdq/nodes->items (:content (md/parse "---"))))]
      (is (= {:thematic-break nil} item))))

  (testing "block quote item"
    (let [{:keys [block-quote]} (first (mdq/nodes->items
                                         (:content (md/parse "> hello"))))]
      (is (vector? block-quote))))

  (testing "section grouping"
    (let [items (mdq/nodes->items (:content (md/parse "# Title\nParagraph\n\n## Sub\nMore")))]
      (is (= 1 (count items)))
      (let [{:keys [section]} (first items)]
        (is (= 1 (:depth section)))
        (is (= "Title" (:title section)))
        (is (vector? (:body section)))))))

(deftest format-output-json-test
  (testing "JSON typed wrapper objects"
    (let [nodes (:content (md/parse "Hello **bold**"))
          output (mdq/format-output nodes {:output "json"})
          parsed (json/parse-string output true)]
      (is (vector? (:items parsed)))
      (is (contains? (first (:items parsed)) :paragraph)))))

(deftest plain-text-output-test
  (testing "plain text output"
    (let [nodes (:content (md/parse "Hello **bold** and *italic*"))
          output (mdq/format-output nodes {:output "plain"})]
      (is (= "Hello bold and italic" (clojure.string/trim output))))))

(deftest thematic-break-separator-test
  (testing "thematic break separators between items"
    (let [nodes (:content (md/parse "# A\nfoo\n\n# B\nbar"))
          result (mdq/run-pipeline nodes "#")]
      (is (clojure.string/includes?
            (mdq/format-output result {:output "markdown"})
            "---")))))

(deftest json-footnotes-test
  (testing "JSON footnotes"
    (let [md "Text with footnote.[^a]\n\n[^a]: The footnote content."
          ast (md/parse md)
          output (mdq/format-output (:content ast) {:output "json" :ast ast})
          parsed (json/parse-string output true)]
      (is (contains? parsed :footnotes)))))

(deftest cli-link-format-test
  (testing "parse link format flag"
    (let [opts (mdq/parse-args ["--link-format" "reference" "# test"])]
      (is (= "reference" (:link-format opts))))
    (let [opts (mdq/parse-args ["--link-format" "inline" "# test"])]
      (is (= "inline" (:link-format opts)))))
  (testing "parse link placement flag"
    (let [opts (mdq/parse-args ["--link-placement" "doc" "# test"])]
      (is (= "doc" (:link-placement opts))))
    (let [opts (mdq/parse-args ["--link-placement" "section" "# test"])]
      (is (= "section" (:link-placement opts))))))

(deftest reference-link-output-test
  (testing "reference form link output"
    (let [md "[Google](https://google.com) and [GitHub](https://github.com)"
          nodes (:content (md/parse md))
          output (mdq/format-output nodes {:output "markdown" :link-format "reference"})]
      (is (clojure.string/includes? output "[Google][1]"))
      (is (clojure.string/includes? output "[GitHub][2]"))
      (is (clojure.string/includes? output "[1]: https://google.com"))
      (is (clojure.string/includes? output "[2]: https://github.com"))))
  (testing "inline form (explicit)"
    (let [md "[Google](https://google.com)"
          nodes (:content (md/parse md))
          output (mdq/format-output nodes {:output "markdown" :link-format "inline"})]
      (is (clojure.string/includes? output "[Google](https://google.com)"))))
  (testing "reference deduplication"
    (let [md "[A](https://x.com) and [B](https://x.com)"
          nodes (:content (md/parse md))
          output (mdq/format-output nodes {:output "markdown" :link-format "reference"})]
      (is (clojure.string/includes? output "[A][1]"))
      (is (clojure.string/includes? output "[B][1]"))
      (is (= 1 (count (re-seq #"\[1\]: https://x.com" output)))))))

(deftest table-alignment-test
  (testing "extract table alignments"
    (let [md "| L | C | R | N |\n|:--|:--:|--:|---|\n| a | b | c | d |"
          alignments (mdq/extract-table-alignments md)]
      (is (= [["left" "center" "right" "none"]] alignments))))

  (testing "alignment in markdown output"
    (let [md "| L | C | R |\n|:--|:--:|--:|\n| a | b | c |"
          ast (md/parse md)
          alignments (mdq/extract-table-alignments md)
          nodes (mdq/attach-table-alignments (:content ast) alignments)
          output (mdq/emit-markdown nodes)]
      (is (clojure.string/includes? output ":---"))
      (is (clojure.string/includes? output ":---:"))
      (is (clojure.string/includes? output "---:"))))

  (testing "alignment in data model"
    (let [md "| L | C | R |\n|:--|:--:|--:|\n| a | b | c |"
          ast (md/parse md)
          alignments (mdq/extract-table-alignments md)
          nodes (mdq/attach-table-alignments (:content ast) alignments)
          {:keys [table]} (first (mdq/nodes->items nodes))]
      (is (= ["left" "center" "right"] (:alignments table)))))

  (testing "alignment preserved through column filtering"
    (let [md "| L | C | R |\n|:--|:--:|--:|\n| a | b | c |"
          ast (md/parse md)
          alignments (mdq/extract-table-alignments md)
          nodes (mdq/attach-table-alignments (:content ast) alignments)
          result (mdq/run-pipeline nodes ":-: L")
          output (mdq/emit-markdown result)]
      (is (clojure.string/includes? output ":---"))
      (is (not (clojure.string/includes? output ":---:")))
      (is (not (clojure.string/includes? output "---:"))))))

(deftest link-placement-test
  (testing "section placement — refs after each section"
    (let [md "# A\n\n[Link A](https://a.com)\n\n# B\n\n[Link B](https://b.com)"
          nodes (:content (md/parse md))
          output (mdq/format-output nodes {:output "markdown"
                                           :link-format "reference"
                                           :link-placement "section"})
          idx1 (.indexOf output "[1]: https://a.com")
          idx-b (.indexOf output "# B")]
      (is (clojure.string/includes? output "[Link A][1]"))
      (is (clojure.string/includes? output "[1]: https://a.com"))
      (is (clojure.string/includes? output "[Link B][2]"))
      (is (clojure.string/includes? output "[2]: https://b.com"))
      (is (< idx1 idx-b))))

  (testing "section placement — dedup across sections"
    (let [md "# A\n\n[X](https://same.com)\n\n# B\n\n[Y](https://same.com)"
          nodes (:content (md/parse md))
          output (mdq/format-output nodes {:output "markdown"
                                           :link-format "reference"
                                           :link-placement "section"})]
      (is (clojure.string/includes? output "[X][1]"))
      (is (clojure.string/includes? output "[Y][1]"))
      (is (= 1 (count (re-seq #"\[1\]: https://same.com" output))))))

  (testing "doc placement — refs at document end"
    (let [md "# A\n\n[Link A](https://a.com)\n\n# B\n\n[Link B](https://b.com)"
          nodes (:content (md/parse md))
          output (mdq/format-output nodes {:output "markdown"
                                           :link-format "reference"
                                           :link-placement "doc"})
          idx1 (.indexOf output "[1]: https://a.com")
          idx-b (.indexOf output "# B")]
      (is (> idx1 idx-b))))

  (testing "default placement is section"
    (let [md "# A\n\n[Link](https://a.com)\n\n# B\n\nNo links here"
          nodes (:content (md/parse md))
          output (mdq/format-output nodes {:output "markdown"})
          idx1 (.indexOf output "[1]: https://a.com")
          idx-b (.indexOf output "# B")]
      (is (clojure.string/includes? output "[Link][1]"))
      (is (< idx1 idx-b)))))
