(ns mdq-test
  (:require [clojure.test :refer [deftest is testing]]
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
      (let [sections (mdq/slice-sections 1 nodes)]
        (is (= 2 (count sections)))
        (is (= "A" (get-in (first sections) [:heading :content 0 :text])))))
    (testing "level 2 slicing"
      (let [sections (mdq/slice-sections 2 nodes)]
        (is (= 3 (count sections)))))))

(deftest section-filter-test
  (let [nodes (:content (md/parse "# Intro\nHello\n\n## Sub\nDetail\n\n# API\nEndpoints"))]
    (testing "filter by text"
      (let [result (mdq/section-filter {:level 1 :matcher (mdq/parse-text-matcher "intro")} nodes)]
        (is (= 4 (count result)))
        (is (= :heading (:type (first result))))))
    (testing "filter all sections (no matcher)"
      (let [result (mdq/section-filter {:level 1 :matcher nil} nodes)]
        (is (= 6 (count result)))))))

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
      (is (= "- a\n- b\n- c" (mdq/emit-markdown nodes))))))

(deftest format-output-test
  (let [nodes (:content (md/parse "# Test"))]
    (testing "markdown format"
      (is (string? (mdq/format-output nodes {:output "markdown"}))))
    (testing "json format"
      (let [out (mdq/format-output nodes {:output "json"})]
        (is (clojure.string/includes? out "\"items\""))))
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
      (is (= 3 (count (mdq/run-pipeline nodes "-")))))))

(deftest task-filter-test
  (let [nodes (:content (md/parse "- [ ] todo 1\n- [x] done 1\n- [ ] todo 2"))]
    (testing "unchecked"
      (is (= 2 (count (mdq/run-pipeline nodes "- [ ]")))))
    (testing "checked"
      (is (= 1 (count (mdq/run-pipeline nodes "- [x]")))))
    (testing "any"
      (is (= 3 (count (mdq/run-pipeline nodes "- [?]")))))))

(deftest blockquote-filter-test
  (let [nodes (:content (md/parse "> quote one\n\n> quote two\n\nNot a quote"))]
    (testing "match all blockquotes"
      (is (= 2 (count (mdq/run-pipeline nodes ">")))))
    (testing "match by text"
      (is (= 1 (count (mdq/run-pipeline nodes "> one")))))))

(deftest code-filter-test
  (let [nodes (:content (md/parse "```clojure\n(+ 1 2)\n```\n\n```python\nprint('hi')\n```"))]
    (testing "filter by language"
      (is (= 1 (count (mdq/run-pipeline nodes "```clojure")))))
    (testing "match all code"
      (is (= 2 (count (mdq/run-pipeline nodes "```")))))))

(deftest paragraph-filter-test
  (let [nodes (:content (md/parse "Hello world.\n\nGoodbye world.\n\n# Heading"))]
    (testing "match by text"
      (is (= 1 (count (mdq/run-pipeline nodes "P: hello")))))
    (testing "match all paragraphs"
      (is (= 2 (count (mdq/run-pipeline nodes "P:")))))))

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
      (is (= 2 (count (mdq/run-pipeline nodes "# Setup | -")))))))

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
