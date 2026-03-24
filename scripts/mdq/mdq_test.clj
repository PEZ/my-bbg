(ns mdq-test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.string]
            [mdq]
            [nextjournal.markdown :as md]))

(deftest split-pipeline-test
  (testing "simple split"
    (is (= [{:text "# A" :offset 1}
            {:text "## B" :offset 7}]
           (#'mdq/split-pipeline "# A | ## B"))))
  (testing "single selector"
    (is (= [{:text "# A" :offset 1}]
           (#'mdq/split-pipeline "# A"))))
  (testing "preserves regex pipes"
    (is (= [{:text "# /a|b/" :offset 1}]
           (#'mdq/split-pipeline "# /a|b/"))))
  (testing "preserves quoted pipes"
    (is (= [{:text "# \"a|b\"" :offset 1}]
           (#'mdq/split-pipeline "# \"a|b\""))))
  (testing "empty input"
    (is (= [] (#'mdq/split-pipeline ""))))
  (testing "trims whitespace and keeps absolute offsets"
    (is (= [{:text "# A" :offset 3}
            {:text "## B" :offset 11}]
           (#'mdq/split-pipeline "  # A  |  ## B  ")))))

(deftest pest-error-formatting-test
  (testing "default single-column pointer"
    (is (= "Syntax error in select specifier:\n --> 1:1\n  |\n1 | ~\n  | ^---\n  |\n  = expected valid query"
           (#'mdq/format-pest-error {:col 1
                                   :message "expected valid query"
                                   :input "~"}))))
  (testing "point pointer"
    (is (= "Syntax error in select specifier:\n --> 1:4\n  |\n1 | # /\\P{/\n  |    ^\n  |\n  = regex parse error: Unicode escape not closed"
           (#'mdq/format-pest-error {:col 4
                                   :message "regex parse error: Unicode escape not closed"
                                   :input "# /\\P{/"
                                   :pointer-style :point}))))
  (testing "default range pointer"
    (is (= "Syntax error in select specifier:\n --> 1:7\n  |\n1 | # \"\\u{FFFFFF}\"\n  |       ^----^\n  |\n  = invalid unicode sequence: FFFFFF"
           (#'mdq/format-pest-error {:col 7
                                   :end-col 11
                                   :message "invalid unicode sequence: FFFFFF"
                                   :input "# \"\\u{FFFFFF}\""}))))
  (testing "tight range pointer"
    (is (= "Syntax error in select specifier:\n --> 1:4\n  |\n1 | +++other\n  |    ^---^\n  |\n  = front matter language must be \"toml\" or \"yaml\". Found \"other\"."
           (#'mdq/format-pest-error {:col 4
                                   :end-col 8
                                   :message "front matter language must be \"toml\" or \"yaml\". Found \"other\"."
                                   :input "+++other"
                                   :pointer-style :tight-range})))))

(deftest throw-parse-error-test
  (testing "keeps relative columns without a segment offset"
    (let [error (try
                  (#'mdq/throw-parse-error {:parse/input "# bad"} 3 "boom" :end-col 5)
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (is (instance? clojure.lang.ExceptionInfo error))
      (is (= {:type :parse-error
              :col 3
              :end-col 5
              :message "boom"
              :input "# bad"}
             (select-keys (ex-data error)
                          [:type :col :end-col :message :input])))))
  (testing "applies pipeline segment offsets to absolute columns"
    (let [error (try
                  (#'mdq/throw-parse-error {:parse/input "# * | bad"
                                            :parse/offset 6}
                                           1
                                           "boom"
                                           :end-col 3)
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (is (instance? clojure.lang.ExceptionInfo error))
      (is (= {:type :parse-error
              :col 7
              :end-col 9
              :message "boom"
              :input "# * | bad"}
             (select-keys (ex-data error)
                          [:type :col :end-col :message :input]))))))

(deftest parse-text-matcher-test
  (testing "nil/empty/wildcard returns nil"
    (is (nil? (#'mdq/parse-text-matcher {} nil)))
    (is (nil? (#'mdq/parse-text-matcher {} "")))
    (is (nil? (#'mdq/parse-text-matcher {} "*"))))
  (testing "unquoted case-insensitive"
    (let [m (#'mdq/parse-text-matcher {} "hello")]
      (is (m "Hello World"))
      (is (m "HELLO"))
      (is (not (m "world")))))
  (testing "quoted case-sensitive"
    (let [m (#'mdq/parse-text-matcher {} "\"Hello\"")]
      (is (m "Hello World"))
      (is (not (m "hello world")))))
  (testing "regex"
    (let [m (#'mdq/parse-text-matcher {} "/hel+o/")]
      (is (m "hello"))
      (is (m "helllo"))
      (is (not (m "heo")))))
  (testing "anchored start"
    (let [m (#'mdq/parse-text-matcher {} "^intro")]
      (is (m "Introduction"))
      (is (not (m "the intro")))))
  (testing "anchored end"
    (let [m (#'mdq/parse-text-matcher {} "api$")]
      (is (m "REST API"))
      (is (not (m "API docs")))))
  (testing "anchored both"
    (let [m (#'mdq/parse-text-matcher {} "^hello$")]
      (is (m "HELLO"))
      (is (not (m "hello world")))))
  (testing "regex replace returns map"
    (let [m (#'mdq/parse-text-matcher {} "!s/foo/bar/")]
      (is (map? m))
      (is (#'mdq/text-matches? m "foo bar"))
      (is (not (#'mdq/text-matches? m "baz"))))))

(deftest parse-selector-test
  (testing "section selector"
    (is (= :section (:type (#'mdq/parse-selector {:parse/input "# hello"} "# hello"))))
    (is (nil? (:level (#'mdq/parse-selector {:parse/input "# hello"} "# hello"))))
    (is (= 2 (:level (#'mdq/parse-selector {:parse/input "## hello"} "## hello"))))
    (is (= 3 (:level (#'mdq/parse-selector {:parse/input "###"} "###")))))
  (testing "section without text has nil matcher"
    (is (nil? (:matcher (#'mdq/parse-selector {:parse/input "#"} "#")))))
  (testing "single-column table selectors remain valid"
    (is (= :table (:type (#'mdq/parse-selector {:parse/input ":-: Name"} ":-: Name")))))
  (testing "phase 2 dispatch rejections return parse errors at col 1"
    (doseq [selector ["\"hello\"" "~" "2. hello" ":-: *" "P *" "P : *"]]
      (let [error (try
                    (#'mdq/parse-selector {:parse/input selector} selector)
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is (instance? clojure.lang.ExceptionInfo error)
            (str selector " should throw ExceptionInfo"))
        (is (= {:type :parse-error
                :col 1
                :message "expected valid query"
                :input selector}
               (select-keys (ex-data error) [:type :col :message :input]))
            (str selector " should report the phase-2 valid-query parse error")))))
  (testing "phase 3 structural rejections return precise parse errors"
    (doseq [[selector expected]
            [["#foo"
              {:type :parse-error
               :col 2
               :message "expected end of input, space, or section options"
               :input "#foo"}]
             ["# $hello^"
              {:type :parse-error
               :col 3
               :message "expected end of input, \"*\", unquoted string, regex, quoted string, or \"^\""
               :input "# $hello^"}]
             ["- [*]"
              {:type :parse-error
               :col 4
               :message "expected \"[x]\", \"[x]\", or \"[?]\""
               :input "- [*]"}]]]
      (let [error (try
                    (#'mdq/parse-selector {:parse/input selector} selector)
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is (instance? clojure.lang.ExceptionInfo error)
            (str selector " should throw ExceptionInfo"))
        (is (= expected
               (select-keys (ex-data error) [:type :col :message :input]))
            (str selector " should report the phase-3 structural parse error")))))
  (testing "phase 4 unclosed delimiters return precise parse errors"
    (doseq [[selector expected]
            [["# \"hello"
              {:type :parse-error
               :col 9
               :message "expected character in quoted string"
               :input "# \"hello"}]
             ["# 'hello"
              {:type :parse-error
               :col 9
               :message "expected character in quoted string"
               :input "# 'hello"}]
             ["# /hello"
              {:type :parse-error
               :col 9
               :message "expected regex character"
               :input "# /hello"}]
             ["[](http"
              {:type :parse-error
               :col 8
               :message "expected \"$\""
               :input "[](http"}]]]
      (let [error (try
                    (#'mdq/parse-selector {:parse/input selector} selector)
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is (instance? clojure.lang.ExceptionInfo error)
            (str selector " should throw ExceptionInfo"))
        (is (= expected
               (select-keys (ex-data error) [:type :col :message :input]))
            (str selector " should report the phase-4 unclosed-delimiter parse error")))))
  (testing "phase 5 escape-sequence validation returns precise parse errors"
    (doseq [[selector expected]
            [["# \"\\x\""
              {:type :parse-error
               :col 5
               :message "expected \", ', `, \\, n, r, or t"
               :input "# \"\\x\""}]
             ["# \"\\u{snowman}\""
              {:type :parse-error
               :col 7
               :message "expected 1 - 6 hex characters"
               :input "# \"\\u{snowman}\""}]
             ["# \"\\u{}\""
              {:type :parse-error
               :col 7
               :message "expected 1 - 6 hex characters"
               :input "# \"\\u{}\""}]
             ["# \"\\u{1234567}\""
              {:type :parse-error
               :col 5
               :message "expected \", ', `, \\, n, r, or t"
               :input "# \"\\u{1234567}\""}]
             ["# \"\\u{FFFFFF}\""
              {:type :parse-error
               :col 7
               :end-col 11
               :message "invalid unicode sequence: FFFFFF"
               :input "# \"\\u{FFFFFF}\""}]]]
      (let [error (try
                    (#'mdq/parse-selector {:parse/input selector} selector)
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is (instance? clojure.lang.ExceptionInfo error)
            (str selector " should throw ExceptionInfo"))
        (is (= expected
               (select-keys (ex-data error) [:type :col :end-col :message :input]))
            (str selector " should report the phase-5 escape parse error")))))
  (testing "phase 6 targeted validations return precise parse errors"
    (doseq [[selector expected]
            [["# /\\P{/"
              {:type :parse-error
               :col 4
               :message "regex parse error: Unicode escape not closed"
               :input "# /\\P{/"}]
             [":-: :-: row"
              {:type :parse-error
               :col 5
               :message "table column matcher cannot empty; use an explicit \"*\""
               :input ":-: :-: row"}]
             ["+++other"
              {:type :parse-error
               :col 4
               :end-col 8
               :message "front matter language must be \"toml\" or \"yaml\". Found \"other\"."
               :input "+++other"}]
             ["</> <span>"
              {:type :parse-error
               :col 5
               :message "expected end of input, \"*\", unquoted string, regex, quoted string, or \"^\""
               :input "</> <span>"}]]]
      (let [error (try
                    (#'mdq/parse-selector {:parse/input selector} selector)
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is (instance? clojure.lang.ExceptionInfo error)
            (str selector " should throw ExceptionInfo"))
        (is (= expected
               (select-keys (ex-data error) [:type :col :end-col :message :input]))
            (str selector " should report the phase-6 targeted parse error"))))))

(deftest slice-sections-test
  (let [nodes (:content (md/parse "# A\nfoo\n\n# B\nbar\n\n## C\nbaz"))]
    (testing "level 1 slicing"
      (let [sections (#'mdq/slice-sections {:level 1} nodes)]
        (is (= 2 (count sections)))
        (is (= "A" (get-in (first sections) [:heading :content 0 :text])))))
    (testing "level 2 slicing"
      (let [sections (#'mdq/slice-sections {:level 2} nodes)]
        (is (= 3 (count sections)))))))

(deftest section-filter-test
  (let [nodes (:content (md/parse "# Intro\nHello\n\n## Sub\nDetail\n\n# API\nEndpoints"))]
    (testing "filter by text"
      (let [result (#'mdq/section-filter {:level 1 :matcher (#'mdq/parse-text-matcher {} "intro")} nodes)]
        (is (= 4 (count result)))
        (is (= :heading (:type (first result))))))
    (testing "filter all sections (no matcher)"
      (let [result (#'mdq/section-filter {:level 1 :matcher nil} nodes)]
        (is (= 6 (count (#'mdq/strip-separators result))))))))

(deftest run-pipeline-test
  (let [nodes (:content (md/parse "# A\nfoo\n\n## B\nbar\n\n# C\nbaz"))]
    (testing "single selector"
      (is (= "# A\n\nfoo\n\n## B\n\nbar" (#'mdq/emit-markdown (#'mdq/run-pipeline nodes "# A")))))
    (testing "piped selectors"
      (is (= "## B\n\nbar" (#'mdq/emit-markdown (#'mdq/run-pipeline nodes "# A | ## B")))))
    (testing "invalid later pipeline stage reports absolute column"
      (let [selector "# * | :-: *"
            error (try
                    (#'mdq/run-pipeline nodes selector)
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is (instance? clojure.lang.ExceptionInfo error))
        (is (= {:type :parse-error
                :col 7
                :message "expected end of input or selector"
                :input selector}
               (select-keys (ex-data error) [:type :col :message :input])))))
    (testing "phase 4 errors keep absolute columns across pipelines"
      (let [selector "# * | # /hello"
            error (try
                    (#'mdq/run-pipeline nodes selector)
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is (instance? clojure.lang.ExceptionInfo error))
        (is (= {:type :parse-error
                :col 15
                :message "expected regex character"
                :input selector}
               (select-keys (ex-data error) [:type :col :message :input])))))
    (testing "phase 5 errors keep absolute columns across pipelines"
      (let [selector "# * | # \"\\x\""
            error (try
                    (#'mdq/run-pipeline nodes selector)
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is (instance? clojure.lang.ExceptionInfo error))
        (is (= {:type :parse-error
                :col 11
                :message "expected \", ', `, \\, n, r, or t"
                :input selector}
               (select-keys (ex-data error) [:type :col :message :input])))))
    (testing "phase 6 range errors keep absolute columns across pipelines"
      (let [selector "# * | +++other"
            error (try
                    (#'mdq/run-pipeline nodes selector)
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is (instance? clojure.lang.ExceptionInfo error))
        (is (= {:type :parse-error
                :col 10
                :end-col 14
                :message "front matter language must be \"toml\" or \"yaml\". Found \"other\"."
                :input selector}
               (select-keys (ex-data error) [:type :col :end-col :message :input])))))))

(deftest emit-markdown-test
  (testing "heading roundtrip"
    (let [nodes (:content (md/parse "## Hello World"))]
      (is (= "## Hello World" (#'mdq/emit-markdown nodes)))))
  (testing "paragraph roundtrip"
    (let [nodes (:content (md/parse "Hello **bold** and *italic*"))]
      (is (= "Hello **bold** and _italic_" (#'mdq/emit-markdown nodes)))))
  (testing "code block roundtrip"
    (let [nodes (:content (md/parse "```clojure\n(+ 1 2)\n```"))]
      (is (= "```clojure\n(+ 1 2)\n```" (#'mdq/emit-markdown nodes)))))
  (testing "bullet list roundtrip"
    (let [nodes (:content (md/parse "- a\n- b\n- c"))]
      (is (= "- a\n- b\n- c" (#'mdq/emit-markdown nodes)))))
  (testing "ordered list roundtrip"
    (let [nodes (:content (md/parse "1. a\n2. b\n3. c"))]
      (is (= "1. a\n2. b\n3. c" (#'mdq/emit-markdown nodes))))))

(deftest format-output-test
  (let [nodes (:content (md/parse "# Test"))]
    (testing "markdown format"
      (is (string? (#'mdq/format-output nodes {:output "markdown"}))))
    (testing "json format"
      (let [out (#'mdq/format-output nodes {:output "json"})
            parsed (json/parse-string out true)]
        (is (vector? (:items parsed)))))
    (testing "edn format"
      (let [out (#'mdq/format-output nodes {:output "edn"})]
        (is (clojure.string/includes? out ":items"))))))

(deftest pre-process-front-matter-test
  (testing "supported front matter delimiters share extraction behavior"
    (doseq [[delimiter format raw]
            [["---" :yaml "title: Test"]
             ["+++" :toml "title = \"Test\""]]]
      (let [input (str delimiter "\n" raw "\n" delimiter "\n# Hello")
            result (#'mdq/pre-process-front-matter input)]
        (is (= format (get-in result [:front-matter :format]))
            (str "format should be " format))
        (is (= raw (get-in result [:front-matter :raw]))
            "front matter content should exclude delimiters")
        (is (= "# Hello" (:body result))
            "body should begin after the closing delimiter"))))
  (testing "unterminated supported delimiters are a no-op"
    (doseq [input ["---\ntitle: Test\n# Hello"
                   "+++\ntitle = \"Test\"\n# Hello"]]
      (let [result (#'mdq/pre-process-front-matter input)]
        (is (nil? (:front-matter result)))
        (is (= input (:body result))))))
  (testing "no front matter"
    (let [result (#'mdq/pre-process-front-matter "# Hello")]
      (is (nil? (:front-matter result)))
      (is (= "# Hello" (:body result))))))

(deftest parse-selector-elements-test
  (testing "unordered list"
    (let [sel (#'mdq/parse-selector {:parse/input "- item"} "- item")]
      (is (= :list-item (:type sel)))
      (is (= :unordered (:list-kind sel)))))
  (testing "ordered list"
    (let [sel (#'mdq/parse-selector {:parse/input "1. item"} "1. item")]
      (is (= :list-item (:type sel)))
      (is (= :ordered (:list-kind sel)))))
  (testing "unchecked task"
    (let [sel (#'mdq/parse-selector {:parse/input "- [ ] todo"} "- [ ] todo")]
      (is (= :task (:type sel)))
      (is (= :unchecked (:task-kind sel)))))
  (testing "checked task"
    (let [sel (#'mdq/parse-selector {:parse/input "- [x] done"} "- [x] done")]
      (is (= :task (:type sel)))
      (is (= :checked (:task-kind sel)))))
  (testing "any task"
    (is (= :any (:task-kind (#'mdq/parse-selector {:parse/input "- [?]"} "- [?]")))))
  (testing "blockquote"
    (is (= :blockquote (:type (#'mdq/parse-selector {:parse/input "> quote"} "> quote")))))
  (testing "code block"
    (let [sel (#'mdq/parse-selector {:parse/input "```clojure"} "```clojure")]
      (is (= :code (:type sel)))))
  (testing "link"
    (let [sel (#'mdq/parse-selector {:parse/input "[text](url)"} "[text](url)")]
      (is (= :link (:type sel)))))
  (testing "image"
    (let [sel (#'mdq/parse-selector {:parse/input "![alt](src)"} "![alt](src)")]
      (is (= :image (:type sel)))))
  (testing "HTML"
    (is (= :html (:type (#'mdq/parse-selector {:parse/input "</> div"} "</> div")))))
  (testing "paragraph"
    (is (= :paragraph (:type (#'mdq/parse-selector {:parse/input "P: hello"} "P: hello"))))))

(deftest list-filter-test
  (let [nodes (:content (md/parse "- foo\n- bar\n- baz"))]
    (testing "match by text"
      (is (= 1 (count (#'mdq/run-pipeline nodes "- foo")))))
    (testing "match all"
      (is (= 3 (count (#'mdq/strip-separators (#'mdq/run-pipeline nodes "-")))))))
  (let [nodes (:content (md/parse "1. Alpha\n2. Beta\n3. Gamma"))]
    (testing "ordered list match all"
      (is (= 3 (count (#'mdq/strip-separators (#'mdq/run-pipeline nodes "1."))))))
    (testing "ordered list match by text"
      (is (= 1 (count (#'mdq/run-pipeline nodes "1. beta")))))
    (testing "ordered list preserves numbers in output"
      (is (= "1. Alpha\n\n   -----\n\n2. Beta\n\n   -----\n\n3. Gamma"
             (#'mdq/emit-markdown (#'mdq/run-pipeline nodes "1.")))))
    (testing "ordered list filtered item preserves its number"
      (is (= "2. Beta"
             (#'mdq/emit-markdown (#'mdq/run-pipeline nodes "1. beta")))))))

(deftest task-filter-test
  (let [nodes (:content (md/parse "- [ ] todo 1\n- [x] done 1\n- [ ] todo 2"))]
    (testing "unchecked"
      (is (= 2 (count (#'mdq/strip-separators (#'mdq/run-pipeline nodes "- [ ]"))))))
    (testing "checked"
      (is (= 1 (count (#'mdq/run-pipeline nodes "- [x]")))))
    (testing "any"
      (is (= 3 (count (#'mdq/strip-separators (#'mdq/run-pipeline nodes "- [?]"))))))))

(deftest blockquote-filter-test
  (let [nodes (:content (md/parse "> quote one\n\n> quote two\n\nNot a quote"))]
    (testing "match all blockquotes"
      (is (= 2 (count (#'mdq/strip-separators (#'mdq/run-pipeline nodes ">"))))))
    (testing "match by text"
      (is (= 1 (count (#'mdq/run-pipeline nodes "> one")))))))

(deftest code-filter-test
  (let [nodes (:content (md/parse "```clojure\n(+ 1 2)\n```\n\n```python\nprint('hi')\n```"))]
    (testing "filter by language"
      (is (= 1 (count (#'mdq/run-pipeline nodes "```clojure")))))
    (testing "match all code"
      (is (= 2 (count (#'mdq/strip-separators (#'mdq/run-pipeline nodes "```"))))))))

(deftest paragraph-filter-test
  (let [nodes (:content (md/parse "Hello world.\n\nGoodbye world.\n\n# Heading"))]
    (testing "match by text"
      (is (= 1 (count (#'mdq/run-pipeline nodes "P: hello")))))
    (testing "match all paragraphs"
      (is (= 2 (count (#'mdq/strip-separators (#'mdq/run-pipeline nodes "P:"))))))))

(deftest link-filter-test
  (let [nodes (:content (md/parse "[Google](https://google.com)\n\n[GitHub](https://github.com)"))]
    (testing "filter by URL"
      (is (= 1 (count (#'mdq/run-pipeline nodes "[](github)")))))
    (testing "filter by display text"
      (is (= 1 (count (#'mdq/run-pipeline nodes "[Google]()")))))))

(deftest image-filter-test
  (let [nodes (:content (md/parse "![Logo](logo.png)\n\n![Banner](banner.jpg)"))]
    (testing "filter by alt text"
      (is (= 1 (count (#'mdq/run-pipeline nodes "![Logo]()")))))
    (testing "filter by src"
      (is (= 1 (count (#'mdq/run-pipeline nodes "![](banner)")))))))

(deftest simple-filter-registry-test
  (let [nodes (:content (md/parse "[Google](https://google.com)\n\n[GitHub](https://github.com)\n\n```clojure\n(+ 1 2)\n```\n\n```python\nprint('hi')\n```"))]
    (testing "walk-ast traverses root then descendants in pre-order"
      (is (= [:root :paragraph :link :text :paragraph :link :text :code :text :code :text]
             (mapv :type (#'mdq/walk-ast nodes)))))
    (testing "shared simple-filter handles link text and url matchers"
      (let [simple-filter #'mdq/simple-filter]
        (is (= 1 (count (simple-filter (#'mdq/parse-selector {:parse/input "[](github)"} "[](github)") nodes)))
            "URL matcher should narrow link matches")
        (is (= 1 (count (simple-filter (#'mdq/parse-selector {:parse/input "[Google]()"} "[Google]()") nodes)))
            "Text matcher should narrow link matches")))
    (testing "shared simple-filter handles code language and body matchers"
      (let [simple-filter #'mdq/simple-filter]
        (is (= 1 (count (simple-filter (#'mdq/parse-selector {:parse/input "```clojure"} "```clojure") nodes)))
            "Language matcher should narrow code matches")
        (is (= 1 (count (simple-filter (#'mdq/parse-selector {:parse/input "``` * (+ 1 2)"} "``` * (+ 1 2)") nodes)))
            "Body matcher should narrow code matches")))))

(deftest parse-args-test
  (testing "basic selector"
    (is (= "# hello" (:selector (#'mdq/parse-args ["# hello"])))))
  (testing "selector with flags"
    (let [opts (#'mdq/parse-args ["-o" "json" "# hello"])]
      (is (= "json" (:output opts)))
      (is (= "# hello" (:selector opts)))))
  (testing "selector starting with dash"
    (is (= "- foo" (:selector (#'mdq/parse-args ["- foo"])))))
  (testing "quiet flag"
    (is (true? (:quiet (#'mdq/parse-args ["-q" "# test"])))))
  (testing "double-dash separator"
    (is (= "- foo" (:selector (#'mdq/parse-args ["--" "- foo"]))))))

(deftest piped-element-selectors-test
  (let [nodes (:content (md/parse "# Setup\n- item 1\n- item 2\n\n# API\n- endpoint 1\n"))]
    (testing "section then list"
      (is (= 2 (count (#'mdq/strip-separators (#'mdq/run-pipeline nodes "# Setup | -"))))))))

(deftest front-matter-filter-test
  (testing "filter YAML front matter"
    (let [fm-node {:type :front-matter :format :yaml :raw "title: Hello\nauthor: PEZ"}
          other-nodes (:content (md/parse "# Heading\nContent"))
          all-nodes (cons fm-node other-nodes)]
      (testing "match any front matter with +++"
        (is (= 1 (count (#'mdq/run-pipeline all-nodes "+++")))))
      (testing "match yaml format specifically"
        (is (= 1 (count (#'mdq/run-pipeline all-nodes "+++yaml")))))
      (testing "no match for toml format"
        (is (empty? (#'mdq/run-pipeline all-nodes "+++toml"))))
      (testing "match by content text"
        (is (= 1 (count (#'mdq/run-pipeline all-nodes "+++yaml PEZ")))))
      (testing "no match when content doesn't match"
        (is (empty? (#'mdq/run-pipeline all-nodes "+++yaml nonexistent"))))))
  (testing "filter TOML front matter"
    (let [fm-node {:type :front-matter :format :toml :raw "title = \"Test\"\ndate = 2024"}
          other-nodes (:content (md/parse "# Content"))
          all-nodes (cons fm-node other-nodes)]
      (testing "match toml format"
        (is (= 1 (count (#'mdq/run-pipeline all-nodes "+++toml")))))
      (testing "no match for yaml"
        (is (empty? (#'mdq/run-pipeline all-nodes "+++yaml"))))
      (testing "match toml with content"
        (is (= 1 (count (#'mdq/run-pipeline all-nodes "+++toml Test")))))))
  (testing "output front matter in markdown"
    (let [fm-node {:type :front-matter :format :yaml :raw "title: Doc"}
          result (#'mdq/emit-markdown [fm-node])]
      (is (clojure.string/includes? result "---"))
      (is (clojure.string/includes? result "title: Doc")))))

(deftest table-filter-test
  (let [table-md "| Name | Age | City |\n|------|-----|------|\n| Alice | 30 | NYC |\n| Bob | 25 | LA |\n| Charlie | 35 | SF |"
        nodes (:content (md/parse table-md))]
    (testing "filter single column by name"
      (let [result (#'mdq/run-pipeline nodes ":-: Name")
            output (#'mdq/emit-markdown result)]
        (is (= 1 (count result)))
        (is (clojure.string/includes? output "Name"))
        (is (not (clojure.string/includes? output "Age")))
        (is (not (clojure.string/includes? output "City")))))
    (testing "filter multiple columns with wildcard, then filter rows"
      (let [result (#'mdq/run-pipeline nodes ":-: * :-: Alice")
            output (#'mdq/emit-markdown result)]
        (is (= 1 (count result)))
        (is (clojure.string/includes? output "Alice"))
        (is (not (clojure.string/includes? output "Bob")))
        (is (not (clojure.string/includes? output "Charlie")))))
    (testing "filter specific column and specific row"
      (let [result (#'mdq/run-pipeline nodes ":-: Name :-: Alice")
            output (#'mdq/emit-markdown result)]
        (is (clojure.string/includes? output "Alice"))
        (is (not (clojure.string/includes? output "Age")))
        (is (not (clojure.string/includes? output "Bob")))))
    (testing "filter rows by city"
      (let [result (#'mdq/run-pipeline nodes ":-: * :-: NYC")
            output (#'mdq/emit-markdown result)]
        (is (clojure.string/includes? output "Alice"))
        (is (clojure.string/includes? output "NYC"))
        (is (not (clojure.string/includes? output "Bob")))))
    (testing "filter columns case-insensitive"
      (let [result (#'mdq/run-pipeline nodes ":-: name")
            output (#'mdq/emit-markdown result)]
        (is (clojure.string/includes? output "Name"))
        (is (not (clojure.string/includes? output "Age")))))))


(deftest raw-table-normalization-and-filtering-test
  (let [raw-table {:type :table
                   :raw-table "| Name | Score |\n|:--|--:|\n| Alice | 10 | extra |\n| Bob |"}
        row->texts (fn [row]
                     (mapv md/node->text (:content row)))]
    (testing "normalize-table-from-raw pads headers, rows, and alignments"
      (let [normalized (#'mdq/normalize-table-from-raw raw-table)]
        (is (true? (:normalized? normalized)))
        (is (nil? (:raw-table normalized)))
        (is (= ["left" "right" "none"] (:alignments normalized)))
        (is (= ["Name" "Score" ""]
               (row->texts (get-in normalized [:content 0 :content 0]))))
        (is (= [["Alice" "10" "extra"]
                ["Bob" "" ""]]
               (mapv row->texts (get-in normalized [:content 1 :content]))))))
    (testing "table-filter retains headers while filtering rows on raw tables"
      (let [result (#'mdq/table-filter {:row-matcher (#'mdq/parse-text-matcher {} "Bob")}
                                       [raw-table])
            filtered (first result)]
        (is (= 1 (count result)))
        (is (= ["Name" "Score" ""]
               (row->texts (get-in filtered [:content 0 :content 0]))))
        (is (= [["Bob" "" ""]]
               (mapv row->texts (get-in filtered [:content 1 :content]))))))
    (testing "table-filter narrows columns after normalization"
      (let [result (#'mdq/table-filter {:col-matcher (#'mdq/parse-text-matcher {} "Score")
                                        :row-matcher (#'mdq/parse-text-matcher {} "Alice")}
                                       [raw-table])
            filtered (first result)]
        (is (= ["Score"]
               (row->texts (get-in filtered [:content 0 :content 0]))))
        (is (= [["10"]]
               (mapv row->texts (get-in filtered [:content 1 :content]))))))))

(deftest regex-replace-test
  (let [nodes (:content (md/parse "Hello world. Goodbye world."))]
    (testing "simple regex replacement"
      (let [result (#'mdq/run-pipeline nodes "P: !s/world/earth/")
            output (#'mdq/emit-markdown result)]
        (is (clojure.string/includes? output "earth"))
        (is (not (clojure.string/includes? output "world")))))
    (testing "regex replacement preserves structure"
      (let [result (#'mdq/run-pipeline nodes "P: !s/world/earth/")]
        (is (= 1 (count result)))
        (is (= :paragraph (:type (first result)))))))
  (testing "replacement in headings"
    (let [nodes (:content (md/parse "# Hello world\n\n## Another world"))]
      (testing "replace text in all headings"
        (let [result (#'mdq/run-pipeline nodes "# !s/world/universe/")
              output (#'mdq/emit-markdown result)]
          (is (clojure.string/includes? output "universe"))
          (is (not (clojure.string/includes? output "world")))))))
  (testing "replacement with regex patterns"
    (let [nodes (:content (md/parse "Version 1.2.3 and version 4.5.6"))]
      (testing "replace digit patterns"
        (let [result (#'mdq/run-pipeline nodes "P: !s/\\d+\\.\\d+\\.\\d+/X.Y.Z/")
              output (#'mdq/emit-markdown result)]
          (is (clojure.string/includes? output "X.Y.Z"))))))
  (testing "replacement in code blocks"
    (let [nodes (:content (md/parse "```clojure\n(def foo 42)\n```"))]
      (testing "replace in code"
        (let [result (#'mdq/run-pipeline nodes "``` * !s/foo/bar/")
              output (#'mdq/emit-markdown result)]
          (is (clojure.string/includes? output "bar"))
          (is (not (clojure.string/includes? output "foo"))))))))

(deftest apply-replacement-cross-boundary-test
  (testing "replacement spanning multiple inline text nodes is redistributed"
    (let [original (first (:content (md/parse "alpha **beta** gamma")))
          result (#'mdq/apply-replacement-cross-boundary original #"beta ga" "BG")]
      (is (= "BG" (get-in result [:content 1 :content 0 :text])))
      (is (= "mma" (get-in result [:content 2 :text])))
      (is (= "alpha **BG**mma" (#'mdq/emit-markdown [result])))))
  (testing "single text-node matches are a no-op"
    (let [original (first (:content (md/parse "alpha beta gamma")))]
      (is (= original
             (#'mdq/apply-replacement-cross-boundary original #"beta" "B"))))))

(deftest anchored-quoted-text-matcher-test
  (testing "anchored quoted start"
    (let [m (#'mdq/parse-text-matcher {} "^\"Hello\"")]
      (is (m "Hello World"))
      (is (not (m "Say Hello")))))
  (testing "anchored quoted end"
    (let [m (#'mdq/parse-text-matcher {} "\"World\"$")]
      (is (m "Hello World"))
      (is (not (m "World Tour")))))
  (testing "anchored quoted both"
    (let [m (#'mdq/parse-text-matcher {} "^\"Hello\"$")]
      (is (m "Hello"))
      (is (not (m "Hello World"))))))

(deftest escape-sequences-test
  (testing "escape sequences in quoted strings"
    (let [m (#'mdq/parse-text-matcher {} "\"hello\\nworld\"")]
      (is (m "hello\nworld"))
      (is (not (m "hello\\nworld"))))
    (let [m (#'mdq/parse-text-matcher {} "\"it\\'s\"")]
      (is (m "it's")))
    (let [m (#'mdq/parse-text-matcher {} "\"back\\\\slash\"")]
      (is (m "back\\slash")))
    (let [m (#'mdq/parse-text-matcher {} "\"snow\\u{2603}man\"")]
      (is (m "snow☃man")))))

(deftest section-level-range-test
  (testing "section level range selectors"
    (let [{:keys [level-range]} (#'mdq/parse-selector {:parse/input "#{2}"} "#{2}")]
      (is (= [2 2] level-range)))
    (let [{:keys [level-range]} (#'mdq/parse-selector {:parse/input "#{2,4}"} "#{2,4}")]
      (is (= [2 4] level-range)))
    (let [{:keys [level-range]} (#'mdq/parse-selector {:parse/input "#{2,}"} "#{2,}")]
      (is (= [2 6] level-range)))
    (let [{:keys [level-range]} (#'mdq/parse-selector {:parse/input "#{,3}"} "#{,3}")]
      (is (= [1 3] level-range))))
  (testing "section level range filtering"
    (let [nodes (:content (md/parse "# H1\nA\n## H2\nB\n### H3\nC\n#### H4\nD"))]
      (is (= 1 (count (#'mdq/slice-sections {:level-range [2 3]} nodes))))
      (is (= "## H2\n\nB\n\n### H3\n\nC\n\n#### H4\n\nD"
             (#'mdq/emit-markdown (#'mdq/run-pipeline nodes "#{2,3}")))))))

(deftest ordered-task-selector-test
  (testing "ordered task selectors"
    (let [{:keys [type task-kind list-kind]} (#'mdq/parse-selector {:parse/input "1. [x] done"} "1. [x] done")]
      (is (= :task type))
      (is (= :checked task-kind))
      (is (= :ordered list-kind)))
    (let [{:keys [type task-kind list-kind]} (#'mdq/parse-selector {:parse/input "1. [ ]"} "1. [ ]")]
      (is (= :task type))
      (is (= :unchecked task-kind))
      (is (= :ordered list-kind)))))

(deftest html-inline-test
  (testing "html inline nodes"
    (let [nodes (:content (md/parse "Some <span>inline</span> here."))]
      (is (pos? (count (#'mdq/run-pipeline nodes "</> span")))))))

(deftest whitespace-trimming-link-selector-test
  (testing "whitespace trimming in link selector"
    (let [nodes (:content (md/parse "[foo](https://example.com)"))]
      (is (pos? (count (#'mdq/run-pipeline nodes "[](  https://example.com  )"))))))
  (testing "whitespace trimming in image selector"
    (let [nodes (:content (md/parse "![alt](https://img.com/pic.png)"))]
      (is (pos? (count (#'mdq/run-pipeline nodes "![](  https://img.com/pic.png  )")))))))

(deftest nodes-items-test
  (testing "paragraph item"
    (let [{:keys [paragraph]} (first (#'mdq/nodes->items
                                       (:content (md/parse "Hello **bold**"))))]
      (is (= "Hello **bold**" paragraph))))

  (testing "code block item"
    (let [{:keys [code-block]} (first (#'mdq/nodes->items
                                        (:content (md/parse "```rust metadata\nfn main() {}\n```"))))]
      (is (= "fn main() {}\n" (:code code-block)))
      (is (= "rust" (:language code-block)))
      (is (= "metadata" (:metadata code-block)))))

  (testing "code block without metadata"
    (let [{:keys [code-block]} (first (#'mdq/nodes->items
                                        (:content (md/parse "```python\nprint('hi')\n```"))))]
      (is (= "python" (:language code-block)))
      (is (nil? (:metadata code-block)))))

  (testing "link item"
    (let [nodes (#'mdq/run-pipeline
                  (:content (md/parse "[Google](https://google.com)"))
                  "[]()")
          {:keys [link]} (first (#'mdq/nodes->items nodes))]
      (is (= "https://google.com" (:url link)))
      (is (= "Google" (:display link)))))

  (testing "list item with index"
    (let [{:keys [list]} (first (#'mdq/nodes->items
                                  (:content (md/parse "1. one\n2. two"))))]
      (is (= 2 (count list)))
      (is (= 1 (:index (first list))))))

  (testing "thematic break item"
    (let [item (first (#'mdq/nodes->items (:content (md/parse "---"))))]
      (is (= {:thematic-break nil} item))))

  (testing "block quote item"
    (let [{:keys [block-quote]} (first (#'mdq/nodes->items
                                         (:content (md/parse "> hello"))))]
      (is (vector? block-quote))))

  (testing "section grouping"
    (let [items (#'mdq/nodes->items (:content (md/parse "# Title\nParagraph\n\n## Sub\nMore")))]
      (is (= 1 (count items)))
      (let [{:keys [section]} (first items)]
        (is (= 1 (:depth section)))
        (is (= "Title" (:title section)))
        (is (vector? (:body section)))))))

(deftest nodes-items-nested-section-grouping-test
  (let [items (#'mdq/nodes->items
               (:content (md/parse "# A\npara\n\n## B\nsub\n\n# C\nend")))]
    (is (= 2 (count items))))
  (let [items (#'mdq/nodes->items
               (:content (md/parse "# A\npara\n\n## B\nsub\n\n# C\nend")))]
    (is (= "A" (get-in items [0 :section :title])))
    (is (= {:paragraph "para"}
           (get-in items [0 :section :body 0])))
    (is (= "B" (get-in items [0 :section :body 1 :section :title])))
    (is (= [{:paragraph "sub"}]
           (get-in items [0 :section :body 1 :section :body])))
    (is (= "C" (get-in items [1 :section :title])))))

(deftest format-output-json-test
  (testing "JSON typed wrapper objects"
    (let [nodes (:content (md/parse "Hello **bold**"))
          output (#'mdq/format-output nodes {:output "json"})
          parsed (json/parse-string output true)]
      (is (vector? (:items parsed)))
      (is (contains? (first (:items parsed)) :document))
      (is (contains? (first (get-in parsed [:items 0 :document])) :paragraph)))))

(deftest format-structured-output-test
  (let [nodes (:content (md/parse "# A\npara\n\n## B\nsub\n\n# C\nend"))]
    (testing "json output without a selector wraps the document"
      (let [parsed (json/parse-string (#'mdq/format-structured-output nodes {} :json) true)]
        (is (contains? (first (:items parsed)) :document))
        (is (= "A" (get-in parsed [:items 0 :document 0 :section :title])))
        (is (= "B" (get-in parsed [:items 0 :document 0 :section :body 1 :section :title])))))
    (testing "json output with a selector omits the document wrapper"
      (let [selected (#'mdq/run-pipeline nodes "#")
            parsed (json/parse-string
                    (#'mdq/format-structured-output selected {:selector "#"} :json)
                    true)]
        (is (= "A" (get-in parsed [:items 0 :section :title])))
        (is (= "C" (get-in parsed [:items 1 :section :title])))
        (is (nil? (get-in parsed [:items 0 :document])))))
    (testing "edn output follows the non-json path without document wrapping"
      (let [parsed (edn/read-string (#'mdq/format-structured-output nodes {} :edn))]
        (is (= "A" (get-in parsed [:items 0 :section :title])))
        (is (= "C" (get-in parsed [:items 1 :section :title])))
        (is (nil? (get-in parsed [:items 0 :document])))))))

(deftest plain-text-output-test
  (testing "plain text output"
    (let [nodes (:content (md/parse "Hello **bold** and *italic*"))
          output (#'mdq/format-output nodes {:output "plain"})]
      (is (= "Hello bold and italic" (clojure.string/trim output))))))

(deftest thematic-break-separator-test
  (testing "thematic break separators between items"
    (let [nodes (:content (md/parse "# A\nfoo\n\n# B\nbar"))
          result (#'mdq/run-pipeline nodes "#")]
      (is (clojure.string/includes?
            (#'mdq/format-output result {:output "markdown"})
            "   -----")))))

(deftest json-footnotes-test
  (testing "JSON footnotes"
    (let [md "Text with footnote.[^a]\n\n[^a]: The footnote content."
          ast (md/parse md)
          output (#'mdq/format-output (:content ast) {:output "json" :ast ast})
          parsed (json/parse-string output true)]
      (is (contains? parsed :footnotes)))))

(deftest cli-link-format-test
  (testing "parse link format flag"
    (let [opts (#'mdq/parse-args ["--link-format" "reference" "# test"])]
      (is (= "reference" (:link-format opts))))
    (let [opts (#'mdq/parse-args ["--link-format" "inline" "# test"])]
      (is (= "inline" (:link-format opts)))))
  (testing "parse link placement flag"
    (let [opts (#'mdq/parse-args ["--link-placement" "doc" "# test"])]
      (is (= "doc" (:link-placement opts))))
    (let [opts (#'mdq/parse-args ["--link-placement" "section" "# test"])]
      (is (= "section" (:link-placement opts))))))

(deftest reference-link-output-test
  (testing "reference form link output"
    (let [md "[Google](https://google.com) and [GitHub](https://github.com)"
          nodes (:content (md/parse md))
          output (#'mdq/format-output nodes {:output "markdown" :link-format "reference"})]
      (is (clojure.string/includes? output "[Google][1]"))
      (is (clojure.string/includes? output "[GitHub][2]"))
      (is (clojure.string/includes? output "[1]: https://google.com"))
      (is (clojure.string/includes? output "[2]: https://github.com"))))
  (testing "inline form (explicit)"
    (let [md "[Google](https://google.com)"
          nodes (:content (md/parse md))
          output (#'mdq/format-output nodes {:output "markdown" :link-format "inline"})]
      (is (clojure.string/includes? output "[Google](https://google.com)"))))
  (testing "reference deduplication"
    (let [md "[A](https://x.com) and [B](https://x.com)"
          nodes (:content (md/parse md))
          output (#'mdq/format-output nodes {:output "markdown" :link-format "reference"})]
      (is (clojure.string/includes? output "[A][1]"))
      (is (clojure.string/includes? output "[B][1]"))
      (is (= 1 (count (re-seq #"\[1\]: https://x.com" output)))))))

(deftest table-alignment-test
  (testing "extract table alignments"
    (let [md "| L | C | R | N |\n|:--|:--:|--:|---|\n| a | b | c | d |"
          alignments (#'mdq/extract-table-alignments md)]
      (is (= [["left" "center" "right" "none"]] alignments))))

  (testing "alignment in markdown output"
    (let [md "| L | C | R |\n|:--|:--:|--:|\n| a | b | c |"
          ast (md/parse md)
          alignments (#'mdq/extract-table-alignments md)
          nodes (#'mdq/attach-table-alignments (:content ast) alignments)
          output (#'mdq/emit-markdown nodes)]
      (is (clojure.string/includes? output ":---"))
      (is (clojure.string/includes? output ":---:"))
      (is (clojure.string/includes? output "---:"))))

  (testing "alignment in data model"
    (let [md "| L | C | R |\n|:--|:--:|--:|\n| a | b | c |"
          ast (md/parse md)
          alignments (#'mdq/extract-table-alignments md)
          nodes (#'mdq/attach-table-alignments (:content ast) alignments)
          {:keys [table]} (first (#'mdq/nodes->items nodes))]
      (is (= ["left" "center" "right"] (:alignments table)))))

  (testing "alignment preserved through column filtering"
    (let [md "| L | C | R |\n|:--|:--:|--:|\n| a | b | c |"
          ast (md/parse md)
          alignments (#'mdq/extract-table-alignments md)
          nodes (#'mdq/attach-table-alignments (:content ast) alignments)
          result (#'mdq/run-pipeline nodes ":-: L")
          output (#'mdq/emit-markdown result)]
      (is (clojure.string/includes? output ":---"))
      (is (not (clojure.string/includes? output ":---:")))
      (is (not (clojure.string/includes? output "---:"))))))

(deftest link-placement-test
  (testing "section placement — refs after each section"
    (let [md "# A\n\n[Link A](https://a.com)\n\n# B\n\n[Link B](https://b.com)"
          nodes (:content (md/parse md))
          output (#'mdq/format-output nodes {:output "markdown"
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
          output (#'mdq/format-output nodes {:output "markdown"
                                           :link-format "reference"
                                           :link-placement "section"})]
      (is (clojure.string/includes? output "[X][1]"))
      (is (clojure.string/includes? output "[Y][1]"))
      (is (= 1 (count (re-seq #"\[1\]: https://same.com" output))))))

  (testing "doc placement — refs at document end"
    (let [md "# A\n\n[Link A](https://a.com)\n\n# B\n\n[Link B](https://b.com)"
          nodes (:content (md/parse md))
          output (#'mdq/format-output nodes {:output "markdown"
                                           :link-format "reference"
                                           :link-placement "doc"})
          idx1 (.indexOf output "[1]: https://a.com")
          idx-b (.indexOf output "# B")]
      (is (> idx1 idx-b))))

  (testing "default placement is section"
    (let [md "# A\n\n[Link](https://a.com)\n\n# B\n\nNo links here"
          nodes (:content (md/parse md))
          output (#'mdq/format-output nodes {:output "markdown"})
          idx1 (.indexOf output "[1]: https://a.com")
          idx-b (.indexOf output "# B")]
      (is (clojure.string/includes? output "[Link][1]"))
      (is (< idx1 idx-b)))))

(deftest emit-markdown-section-placement-test
  (testing "reference definitions are emitted after each section group"
    (let [nodes (:content (md/parse "# A\n\n[One](https://a.com)\n\n## B\n\n[Two](https://b.com)"))
          output (#'mdq/emit-markdown-section-placement
                  [(vec nodes)]
                  "\n\n"
                  (#'mdq/make-emit-context nil
                                           {:link-format "reference"
                                            :link-placement "section"}
                                           nil))
          idx-a-ref (.indexOf output "[1]: https://a.com")
          idx-b-heading (.indexOf output "## B")
          idx-b-ref (.indexOf output "[2]: https://b.com")]
      (is (clojure.string/includes? output "[One][1]"))
      (is (clojure.string/includes? output "[Two][2]"))
      (is (< idx-a-ref idx-b-heading))
      (is (> idx-b-ref idx-b-heading))))
  (testing "repeated URLs are deduplicated across sections"
    (let [nodes (:content (md/parse "# A\n\n[X](https://same.com)\n\n# B\n\n[Y](https://same.com)"))
          output (#'mdq/emit-markdown-section-placement
                  [(vec nodes)]
                  "\n\n"
                  (#'mdq/make-emit-context nil
                                           {:link-format "reference"
                                            :link-placement "section"}
                                           nil))]
      (is (clojure.string/includes? output "[X][1]"))
      (is (clojure.string/includes? output "[Y][1]"))
      (is (= 1 (count (re-seq #"\[1\]: https://same.com" output)))))))

(deftest help-input-short-circuit-test
  (doseq [args [["--help"] ["-h"]]]
    (let [stdin-read? (atom false)
          result (#'mdq/process-inputs args
                                       {:read-stdin (fn []
                                                      (reset! stdin-read? true)
                                                      (throw (ex-info "stdin-read" {})))
                                        :resolve-file (fn [_] (throw (ex-info "resolve-file" {})))})]
      (is (false? @stdin-read?)
          (str "help should not read stdin for " args))
      (is (= 0 (:exit result))
          (str "help should exit 0 for " args))
      (is (clojure.string/includes? (:output result) "Usage: bbg mdq")
          (str "help should produce usage output for " args)))))

(deftest help-text-coverage-test
  (let [output (:output (#'mdq/process "" ["--help"]))]
    (doseq [flag ["--output"
                  "--link-format"
                  "--link-placement"
                  "--link-pos"
                  "--wrap-width"
                  "--renumber-footnotes"
                  "--br"
                  "--no-br"
                  "--quiet"
                  "--help"]]
      (is (clojure.string/includes? output flag)
          (str "help output should mention " flag)))
    (is (not (clojure.string/includes? output "--cwd"))
        "help output should not mention internal --cwd")))
