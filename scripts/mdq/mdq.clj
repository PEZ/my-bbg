(ns mdq
  (:require [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [nextjournal.markdown :as md]))

(def ^:dynamic *selector-input* nil)

(def ^:dynamic *selector-offset* 0)

(defn format-pest-error [{:keys [col end-col message input pointer-style]}]
  (let [col (or col 1)
        input (or input "")
        pointer-pad (apply str (repeat (dec col) " "))
        pointer (cond
                  (= pointer-style :point)
                  "^"

                  (and (= pointer-style :tight-range) end-col)
                  (str "^" (apply str (repeat (max 0 (- end-col col 1)) "-")) "^")

                  end-col
                  (str "^" (apply str (repeat (max 0 (- end-col col)) "-")) "^")

                  :else
                  "^---")]
    (str "Syntax error in select specifier:\n"
         " --> 1:" col "\n"
         "  |\n"
         "1 | " input "\n"
         "  | " pointer-pad pointer "\n"
         "  |\n"
         "  = " message)))

(defn throw-parse-error [col message & {:keys [end-col pointer-style]}]
  (let [absolute-col (+ *selector-offset* col)
        absolute-end-col (when end-col (+ *selector-offset* end-col))]
    (throw (ex-info message
                    (cond-> {:type :parse-error
                             :col absolute-col
                             :message message
                             :input *selector-input*}
                      absolute-end-col (assoc :end-col absolute-end-col)
                      pointer-style (assoc :pointer-style pointer-style))))))

(defn split-pipeline [s]
  (letfn [(segment-entry [segment-text start-offset]
            (let [trimmed (str/trim segment-text)
                  left-trim (- (count segment-text)
                               (count (str/triml segment-text)))]
              {:text trimmed
               :offset (+ start-offset left-trim 1)}))]
    (loop [chars (seq s), current [], current-start 0, idx 0,
           segments [], in-regex false, in-quote nil, prev-char nil]
      (if-not chars
        (let [segment (segment-entry (apply str current) current-start)]
          (cond-> segments
            (seq (:text segment)) (conj segment)))
        (let [c (first chars)]
          (cond
            ;; Regex start: / at token boundary (after space, |, start, or open paren)
            (and (= c \/) (not in-quote) (not in-regex)
                 (or (nil? prev-char) (contains? #{\space \| \(} prev-char)))
            (recur (next chars) (conj current c) current-start (inc idx)
                   segments true in-quote c)

            ;; Regex end: / while in regex
            (and (= c \/) (not in-quote) in-regex)
            (recur (next chars) (conj current c) current-start (inc idx)
                   segments false in-quote c)

            ;; Non-regex /: just a character (e.g., in </>)
            (and (= c \/) (not in-quote) (not in-regex))
            (recur (next chars) (conj current c) current-start (inc idx)
                   segments false in-quote c)

            (and (contains? #{\' \"} c) (not in-regex))
            (recur (next chars) (conj current c) current-start (inc idx)
                   segments in-regex (if (= in-quote c) nil c) c)

            (and (= c \|) (not in-regex) (not in-quote))
            (let [segment (segment-entry (apply str current) current-start)]
              (recur (next chars) [] (inc idx) (inc idx)
                     (conj segments segment) false nil c))

            :else
            (recur (next chars) (conj current c) current-start (inc idx)
                   segments in-regex in-quote c)))))))

(defn process-escape-sequences
  ([s]
   (process-escape-sequences s 1))
  ([s start-col]
   (let [valid-escape-message "expected \", ', `, \\, n, r, or t"
         hex-end-col (fn [hex-start-col hex]
                       (+ hex-start-col (max 0 (- (count hex) 2))))
         parse-unicode-escape
         (fn [idx]
           (let [open-brace-idx (+ idx 2)
                 open-brace (nth s open-brace-idx nil)
                 escaped-col (+ start-col (inc idx))]
             (when (not= open-brace \{)
               (throw-parse-error escaped-col valid-escape-message))
             (let [hex-start-idx (+ idx 3)
                   hex-start-col (+ start-col hex-start-idx)]
               (loop [scan-idx hex-start-idx
                      hex-chars []]
                 (let [current (nth s scan-idx nil)]
                   (cond
                     (nil? current)
                     (throw-parse-error escaped-col valid-escape-message)

                     (= current \})
                     (if (empty? hex-chars)
                       (throw-parse-error hex-start-col "expected 1 - 6 hex characters")
                       (let [hex (apply str hex-chars)]
                         (if (> (count hex) 6)
                           (throw-parse-error escaped-col valid-escape-message)
                           (let [code-point (Integer/parseInt hex 16)]
                             (when-not (Character/isValidCodePoint code-point)
                               (throw-parse-error hex-start-col
                                                  (str "invalid unicode sequence: " hex)
                                                  :end-col (hex-end-col hex-start-col hex)))
                             {:next-idx (inc scan-idx)
                              :piece (String. (Character/toChars code-point))}))))

                     (re-matches #"[0-9a-fA-F]" (str current))
                     (recur (inc scan-idx) (conj hex-chars current))

                     :else
                     (throw-parse-error (if (empty? hex-chars)
                                          hex-start-col
                                          (+ start-col scan-idx))
                                        "expected 1 - 6 hex characters")))))))]
     (loop [idx 0
            pieces []]
       (if (>= idx (count s))
         (apply str pieces)
         (let [ch (nth s idx)]
           (if (not= ch \\)
             (recur (inc idx) (conj pieces (str ch)))
             (let [next-idx (inc idx)
                   escaped (nth s next-idx nil)
                   escaped-col (+ start-col next-idx)]
               (case escaped
                 nil (throw-parse-error escaped-col valid-escape-message)
                 \" (recur (+ idx 2) (conj pieces "\""))
                 \' (recur (+ idx 2) (conj pieces "'"))
                 \` (recur (+ idx 2) (conj pieces "`"))
                 \\ (recur (+ idx 2) (conj pieces "\\"))
                 \n (recur (+ idx 2) (conj pieces "\n"))
                 \r (recur (+ idx 2) (conj pieces "\r"))
                 \t (recur (+ idx 2) (conj pieces "\t"))
                 \u (let [unicode-result (parse-unicode-escape idx)]
                      (recur (:next-idx unicode-result)
                             (conj pieces (:piece unicode-result))))
                 (throw-parse-error escaped-col valid-escape-message))))))))))

(defn parse-text-matcher
  ([s]
   (parse-text-matcher s 1))
  ([s start-col]
   (when (and s (not= s "") (not= s "*"))
     (letfn [(validate-unclosed! [matcher]
               (let [anchored-start (str/starts-with? matcher "^")
                     anchored-end (str/ends-with? matcher "$")
                     without-start (if anchored-start
                                     (subs matcher 1)
                                     matcher)
                     candidate (if anchored-end
                                 (subs without-start 0 (dec (count without-start)))
                                 without-start)
                     candidate-col (+ start-col (if anchored-start 1 0))
                     candidate-first (first candidate)]
                 (cond
                   (and (seq candidate)
                        (or (= \" candidate-first)
                            (= \' candidate-first))
                        (not= (last candidate) candidate-first))
                   (throw-parse-error (+ candidate-col (count candidate))
                                      "expected character in quoted string")

                   (and (str/starts-with? candidate "/")
                        (not (str/ends-with? candidate "/")))
                   (throw-parse-error (+ candidate-col (count candidate))
                                      "expected regex character"))))
             (regex-parse-error-message [pattern description]
               (if (re-find #"\\[pP]\{[^}]*$" pattern)
                 "regex parse error: Unicode escape not closed"
                 (str "regex parse error: " description)))
             (compile-regex [pattern pattern-col]
               (try
                 (re-pattern pattern)
                 (catch java.util.regex.PatternSyntaxException e
                   (throw-parse-error pattern-col
                                      (regex-parse-error-message pattern (.getDescription e))
                                      :pointer-style :point))))]
       (validate-unclosed! s)
       (cond
         ;; Regex replace: !s/pattern/replacement/
         (str/starts-with? s "!s/")
         (let [rest-s (subs s 3)
               sep-idx (str/index-of rest-s "/")
               pattern-str (subs rest-s 0 sep-idx)
               after-sep (subs rest-s (inc sep-idx))
               replacement (if (str/ends-with? after-sep "/")
                             (subs after-sep 0 (dec (count after-sep)))
                             after-sep)
               pattern (compile-regex pattern-str (+ start-col 3))]
           {:match-fn (fn [text] (re-find pattern text))
            :replace {:pattern pattern
                      :replacement replacement}})

         ;; Regex: /pattern/
         (and (str/starts-with? s "/") (str/ends-with? s "/"))
         (let [pattern (compile-regex (subs s 1 (dec (count s)))
                                      (inc start-col))]
           (fn [text] (some? (re-find pattern text))))

         ;; All other cases: strip anchors first, then detect quoting
         :else
         (let [anchored-start (str/starts-with? s "^")
               anchored-end (str/ends-with? s "$")
               s1 (cond-> s
                    anchored-start (subs 1)
                    anchored-end (subs 0 (- (count (cond-> s anchored-start (subs 1))) 1)))
               s1 (str/trim s1)
               quote-char (when (>= (count s1) 2)
                            (let [fc (first s1)]
                              (when (and (or (= \" fc)
                                             (= \' fc))
                                         (= fc (last s1)))
                                fc)))
               quoted? (some? quote-char)
               inner-start-col (+ start-col (if anchored-start 1 0) 1)
               inner (if quoted?
                       (process-escape-sequences (subs s1 1 (dec (count s1)))
                                                 inner-start-col)
                       s1)]
           (when (and (not quoted?) (str/starts-with? inner "<"))
             (throw-parse-error start-col
                                "expected end of input, \"*\", unquoted string, regex, quoted string, or \"^\""))
           (if quoted?
             (cond
               (and anchored-start anchored-end) (fn [text] (= text inner))
               anchored-start (fn [text] (str/starts-with? text inner))
               anchored-end (fn [text] (str/ends-with? text inner))
               :else (fn [text] (str/includes? text inner)))
             (let [text-lower (str/lower-case inner)]
               (cond
                 (and anchored-start anchored-end) (fn [text] (= (str/lower-case text) text-lower))
                 anchored-start (fn [text] (str/starts-with? (str/lower-case text) text-lower))
                 anchored-end (fn [text] (str/ends-with? (str/lower-case text) text-lower))
                 :else (fn [text] (str/includes? (str/lower-case text) text-lower)))))))))))

(defn text-matches? [matcher text]
  (if (map? matcher)
    ((:match-fn matcher) text)
    (matcher text)))

(defn- matcher-start-col [prefix-len selector]
  (let [raw-tail (subs selector prefix-len)
        left-trim (- (count raw-tail) (count (str/triml raw-tail)))]
    (+ prefix-len left-trim 1)))

(defn- parse-front-matter-selector [s]
  (let [rest-s (subs s 3)
        next-char (first rest-s)]
    (if (or (nil? next-char)
            (Character/isWhitespace ^char next-char))
      (let [text-col (matcher-start-col 3 s)
            text (str/trim rest-s)]
        {:type :front-matter
         :format nil
         :matcher (parse-text-matcher text text-col)})
      (let [token-end (or (str/index-of rest-s " ")
                          (count rest-s))
            fmt-str (subs rest-s 0 token-end)
            text-col (when (< token-end (count rest-s))
                       (matcher-start-col (+ 3 token-end) s))
            text (when text-col
                   (str/trim (subs rest-s token-end)))
            format (case fmt-str
                     "yaml" :yaml
                     "toml" :toml
                     nil)]
        (if format
          {:type :front-matter
           :format format
           :matcher (parse-text-matcher text text-col)}
          (throw-parse-error 4
                             (str "front matter language must be \"toml\" or \"yaml\". Found \"" fmt-str "\".")
                             :end-col (+ 3 (count fmt-str))
                             :pointer-style :tight-range))))))

(defn- parse-table-selector [s]
  (let [parts (str/split s #":-:" -1)
        [col-text row-text] (mapv str/trim (rest parts))
        second-delim-col (some-> (str/index-of s ":-:" 3) inc)]
    (when (and second-delim-col
               (str/blank? col-text))
      (throw-parse-error second-delim-col
                         "table column matcher cannot empty; use an explicit \"*\""
                         :pointer-style :point))
    {:type :table
     :col-matcher (parse-text-matcher col-text)
     :row-matcher (parse-text-matcher row-text)}))



(defn parse-selector [s]
  (let [s (str/trim s)
        table-parts (when (str/starts-with? s ":-:")
                      (str/split s #":-:" -1))
        table-col-text (some-> table-parts second str/trim)]
    (cond
      (or (str/starts-with? s "\"")
          (str/starts-with? s "'")
          (str/starts-with? s "~")
          (and (re-find #"^\d" s)
               (not (str/starts-with? s "1.")))
          (and (str/starts-with? s "P")
               (not (str/starts-with? s "P:")))
          (and table-parts
               (< (count table-parts) 3)
               (= "*" table-col-text)))
      (throw-parse-error 1 "expected valid query")

      (str/starts-with? s "#")
      (let [range-match (re-find #"^#\{(\d*)(,?)(\d*)\}(.*)" s)]
        (if range-match
          (let [[_ lo-str comma hi-str rest-text] range-match
                lo (when (seq lo-str) (parse-long lo-str))
                hi (when (seq hi-str) (parse-long hi-str))
                has-comma (= "," comma)
                level-range (cond
                              (and lo (not has-comma)) [lo lo]
                              (and lo hi) [lo hi]
                              (and lo has-comma (not hi)) [lo 6]
                              (and (not lo) has-comma hi) [1 hi])
                text-col (+ (count s) 1 (- (count rest-text)))
                text (str/trim rest-text)]
            {:type :section
             :level-range level-range
             :matcher (parse-text-matcher text text-col)})
          (let [hashes (re-find #"^#+" s)
                level (count hashes)
                next-char (nth s level nil)]
            (when (and next-char
                       (not (Character/isWhitespace ^char next-char))
                       (not= next-char \{))
              (throw-parse-error (inc level)
                                 "expected end of input, space, or section options"))
            (let [text-col (matcher-start-col level s)
                  text (str/trim (subs s level))]
              (when (str/starts-with? text "$")
                (throw-parse-error text-col
                                   "expected end of input, \"*\", unquoted string, regex, quoted string, or \"^\""))
              {:type :section
               :level (when (> level 1) level)
               :matcher (parse-text-matcher text text-col)}))))

      (str/starts-with? s "- [")
      (let [marker-end (str/index-of s "]")
            marker (when marker-end (subs s 2 (inc marker-end)))]
        (if (contains? #{"[x]" "[ ]" "[?]"} marker)
          (let [text-col (matcher-start-col (+ 2 (count marker)) s)
                text (str/trim (subs s (+ 2 (count marker))))
                task-kind (case marker
                            "[x]" :checked
                            "[ ]" :unchecked
                            "[?]" :any)]
            {:type :task
             :task-kind task-kind
             :matcher (parse-text-matcher text text-col)})
          (throw-parse-error 4 "expected \"[x]\", \"[x]\", or \"[?]\"")))

      (str/starts-with? s "1.")
      (let [text-col (matcher-start-col 2 s)
            text (str/trim (subs s 2))
            task-match (re-find #"^\[[ x?]\]" text)]
        (if task-match
          (let [marker (subs text 0 3)
                rest-col (+ (dec text-col) (matcher-start-col 3 text))
                task-kind (case marker
                            "[x]" :checked
                            "[ ]" :unchecked
                            "[?]" :any)
                rest-text (str/trim (subs text 3))]
            {:type :task
             :task-kind task-kind
             :list-kind :ordered
             :matcher (parse-text-matcher rest-text rest-col)})
          {:type :list-item
           :list-kind :ordered
           :matcher (parse-text-matcher text text-col)}))

      (str/starts-with? s "- ")
      (let [text-col (matcher-start-col 2 s)
            text (str/trim (subs s 2))]
        {:type :list-item
         :list-kind :unordered
         :matcher (parse-text-matcher text text-col)})

      (= s "-")
      {:type :list-item
       :list-kind :unordered
       :matcher nil}

      (str/starts-with? s ">")
      (let [text-col (matcher-start-col 1 s)
            text (str/trim (subs s 1))]
        {:type :blockquote
         :matcher (parse-text-matcher text text-col)})

      (str/starts-with? s "```")
      (let [rest-raw (subs s 3)
            has-space (str/starts-with? rest-raw " ")
            rest-col (matcher-start-col 3 s)
            rest-s (str/trim rest-raw)
            [lang text] (when (seq rest-s) (str/split rest-s #"\s+" 2))
            text-col (when (and lang text)
                       (+ (dec rest-col) (matcher-start-col (count lang) rest-s)))]
        (if (and has-space (nil? text))
          {:type :code
           :language-matcher nil
           :matcher (parse-text-matcher lang rest-col)}
          {:type :code
           :language-matcher (parse-text-matcher lang rest-col)
           :matcher (parse-text-matcher text text-col)}))

      (str/starts-with? s "![")
      (let [close-bracket (str/index-of s "](")
            alt-text (when close-bracket (subs s 2 close-bracket))
            after (when close-bracket (subs s (+ 2 close-bracket)))
            end-paren (when after (str/last-index-of after ")"))
            url-col (when close-bracket (+ close-bracket 3))
            url-text (when end-paren (subs after 0 end-paren))]
        (when (and close-bracket after (nil? end-paren))
          (throw-parse-error (inc (count s)) "expected \"$\""))
        {:type :image
         :matcher (parse-text-matcher (some-> alt-text str/trim) 3)
         :url-matcher (parse-text-matcher (some-> url-text str/trim) url-col)})

      (str/starts-with? s "[")
      (let [close-bracket (str/index-of s "](")
            display-text (when close-bracket (subs s 1 close-bracket))
            after (when close-bracket (subs s (+ 2 close-bracket)))
            end-paren (when after (str/last-index-of after ")"))
            url-col (when close-bracket (+ close-bracket 3))
            url-text (when end-paren (subs after 0 end-paren))]
        (when (and close-bracket after (nil? end-paren))
          (throw-parse-error (inc (count s)) "expected \"$\""))
        {:type :link
         :matcher (parse-text-matcher (some-> display-text str/trim) 2)
         :url-matcher (parse-text-matcher (some-> url-text str/trim) url-col)})

      (str/starts-with? s "</>")
      (let [text-col (matcher-start-col 3 s)
            text (str/trim (subs s 3))]
        {:type :html
         :matcher (parse-text-matcher text text-col)})

      (str/starts-with? s "P:")
      (let [text-col (matcher-start-col 2 s)
            text (str/trim (subs s 2))]
        {:type :paragraph
         :matcher (parse-text-matcher text text-col)})

      (str/starts-with? s "+++")
      (parse-front-matter-selector s)

      (str/starts-with? s ":-:")
      (parse-table-selector s)

      :else
      (throw-parse-error 1 "expected valid query"))))

(defn- top-level-sections
  "Extract top-level sections: each heading starts a section, body extends
   until the next heading at <= its level. Headings within a prior section's
   body are skipped (they are subsections)."
  [nodes]
  (let [headings (keep-indexed
                  (fn [idx node]
                    (when (= :heading (:type node))
                      {:idx idx :level (:heading-level node)}))
                  nodes)]
    (loop [[h & more] headings
           skip-until nil
           sections []]
      (if-not h
        sections
        (if (and skip-until (< (:idx h) skip-until))
          (recur more skip-until sections)
          (let [level (:level h)
                end (or (some (fn [h2]
                                (when (and (> (:idx h2) (:idx h))
                                           (<= (:level h2) level))
                                  (:idx h2)))
                              more)
                        (count nodes))
                section {:heading (nth nodes (:idx h))
                         :body (subvec (vec nodes) (inc (:idx h)) end)}]
            (recur more end (conj sections section))))))))

(defn- find-sections-by-text
  "For single # with text matcher: search ALL headings for text match,
   then extract section at matched heading's native level."
  [matcher nodes]
  (let [nodes-vec (vec nodes)]
    (->> nodes-vec
         (keep-indexed
          (fn [idx node]
            (when (and (= :heading (:type node))
                       (text-matches? matcher (md/node->text node)))
              (let [level (:heading-level node)
                    end (or (some (fn [j]
                                   (let [n (nth nodes-vec j)]
                                     (when (and (= :heading (:type n))
                                                (<= (:heading-level n) level))
                                       j)))
                                 (range (inc idx) (count nodes-vec)))
                            (count nodes-vec))]
                {:heading node
                 :body (subvec nodes-vec (inc idx) end)}))))
         vec)))

(defn slice-sections [{:keys [level level-range]} nodes]
  (cond
    level-range
    (let [[lo hi] level-range
          in-range? (fn [node]
                      (and (= :heading (:type node))
                           (<= lo (:heading-level node) hi)))
          heading? (fn [node] (= :heading (:type node)))
          {:keys [sections current]}
          (reduce
           (fn [{:keys [sections current] :as state} node]
             (cond
               (and (in-range? node)
                    (or (nil? current)
                        (<= (:heading-level node) (:heading-level (:heading current)))))
               {:sections (cond-> sections current (conj current))
                :current {:heading node :body []}}

               (and current
                    (heading? node)
                    (not (in-range? node))
                    (<= (:heading-level node) (:heading-level (:heading current))))
               {:sections (conj sections current)
                :current nil}

               current
               {:sections sections
                :current (update current :body conj node)}

               :else state))
           {:sections [] :current nil}
           nodes)]
      (cond-> sections current (conj current)))

    level
    (let [{:keys [sections current]}
          (reduce
           (fn [{:keys [sections current] :as state} node]
             (if (and (= :heading (:type node))
                      (<= (:heading-level node) level))
               {:sections (cond-> sections current (conj current))
                :current {:heading node :body []}}
               (if current
                 {:sections sections
                  :current (update current :body conj node)}
                 state)))
           {:sections [] :current nil}
           nodes)]
      (cond-> sections current (conj current)))

    :else
    (top-level-sections nodes)))

(def result-separator
  "Sentinel node interposed between results by filter functions.
   emit-markdown uses these to place --- separators."
  {:type :result-separator})

(defn strip-separators
  "Remove result-separator sentinels from nodes."
  [nodes]
  (remove #(= :result-separator (:type %)) nodes))

(defn- separate-results
  "Partition nodes by :result-separator sentinels into groups.
   If no separators found, all nodes form one group."
  [nodes]
  (if (some #(= :result-separator (:type %)) nodes)
    (->> nodes
         (partition-by #(= :result-separator (:type %)))
         (remove (fn [group] (= :result-separator (:type (first group)))))
         vec)
    [(vec nodes)]))

(defn section-filter [{:keys [level level-range matcher] :as selector} nodes]
  (let [sections (if (and (nil? level) (nil? level-range) matcher)
                   (find-sections-by-text matcher nodes)
                   (slice-sections selector nodes))]
    (->> sections
         (filter (fn [{:keys [heading]}]
                   (let [hl (:heading-level heading)]
                     (and
                      (if (and level (> level 1))
                        (= hl level)
                        true)
                      (if matcher
                        (text-matches? matcher (md/node->text heading))
                        true)))))
         (map (fn [{:keys [heading body]}]
                (cons heading body)))
         (interpose [result-separator])
         (apply concat))))

(defn walk-ast
  "Lazy depth-first pre-order traversal of AST nodes."
  [nodes]
  (tree-seq (fn [n] (and (map? n) (seq (:content n))))
            :content
            {:type :root :content nodes}))

(defn collect-nodes-deep [types nodes]
  (->> (walk-ast nodes)
       (filterv #(contains? types (:type %)))))

(defn- extract-list-items [list-node]
  (let [ordered? (= :numbered-list (:type list-node))
        start (or (get-in list-node [:attrs :start]) 1)]
    (->> (:content list-node)
         (map-indexed
          (fn [i item]
            (if ordered?
              (assoc-in item [:attrs :order] (+ start i))
              item)))
         (filter #(= :list-item (:type %))))))

(defn list-filter [{:keys [list-kind matcher]} nodes]
  (let [type-set (case list-kind
                   :unordered #{:bullet-list}
                   :ordered #{:numbered-list}
                   #{:bullet-list :numbered-list})
        container-items (->> (collect-nodes-deep type-set nodes)
                             (mapcat extract-list-items))
        direct-items (->> nodes
                          (filter #(and (map? %) (= :list-item (:type %)))))
        items (concat container-items direct-items)]
    (cond->> items
      matcher (filter #(text-matches? matcher (md/node->text %))))))

(defn task-filter [{:keys [task-kind list-kind matcher]} nodes]
  (let [todo-lists (collect-nodes-deep #{:todo-list} nodes)
        filtered-lists (if (= :ordered list-kind)
                         (filter #(contains? (:attrs %) :start) todo-lists)
                         (remove #(contains? (:attrs %) :start) todo-lists))
        container-items (->> filtered-lists
                             (mapcat (fn [tl]
                                       (let [ordered? (contains? (:attrs tl) :start)
                                             start (get-in tl [:attrs :start] 1)]
                                         (map-indexed
                                          (fn [i item]
                                            (if ordered?
                                              (assoc-in item [:attrs :order] (+ start i))
                                              item))
                                          (:content tl)))))
                             (filter #(= :todo-item (:type %))))
        direct-items (->> nodes
                          (filter #(and (map? %) (= :todo-item (:type %)))))
        items (concat container-items direct-items)]
    (cond->> items
      (= :unchecked task-kind) (filter #(not (get-in % [:attrs :checked])))
      (= :checked task-kind) (filter #(get-in % [:attrs :checked]))
      matcher (filter #(text-matches? matcher (md/node->text %))))))

(def ^:private simple-filter-specs
  {:blockquote {:node-pred #(= :blockquote (:type %))
                :preds [[:matcher md/node->text]]}
   :code {:node-pred #(= :code (:type %))
          :preds [[:language-matcher #(or (:language %) "")]
                  [:matcher #(str/join (keep :text (:content %)))]]}
   :paragraph {:node-pred #(#{:paragraph :plain} (:type %))
               :preds [[:matcher md/node->text]]}
   :link {:node-pred #(= :link (:type %))
          :preds [[:matcher md/node->text]
                  [:url-matcher #(get-in % [:attrs :href] "")]]}
   :image {:node-pred #(= :image (:type %))
           :preds [[:matcher md/node->text]
                   [:url-matcher #(get-in % [:attrs :src] "")]]}
   :html {:node-pred #(#{:html-block :html-inline} (:type %))
          :preds [[:matcher #(str/join (keep :text (:content %)))]]}})

(defn- simple-filter [selector nodes]
  (let [{:keys [node-pred preds]} (simple-filter-specs (:type selector))
        xform (apply comp
                     (concat
                      [(filter #(and (map? %) (node-pred %)))]
                      (keep (fn [[selector-key text-fn]]
                              (when-let [matcher (get selector selector-key)]
                                (filter #(text-matches? matcher (text-fn %)))))
                            preds)))]
    (into [] xform (walk-ast nodes))))

(defn front-matter-filter [{:keys [format matcher]} nodes]
  (let [fm-nodes (collect-nodes-deep #{:front-matter} nodes)]
    (cond->> fm-nodes
      format (filter #(= format (:format %)))
      matcher (filter #(text-matches? matcher (or (:raw %) ""))))))

(defn rebuild-table [table col-idxs matched-rows]
  (let [select-cols (fn [{:keys [content] :as row}]
                      (assoc row :content (mapv #(nth content %) col-idxs)))
        [head body] (:content table)
        head-row (first (:content head))
        new-head {:type (:type head)
                  :content [(select-cols head-row)]}
        new-body {:type (:type body)
                  :content (mapv select-cols matched-rows)}
        new-alignments (when-let [aligns (:alignments table)]
                         (mapv #(nth aligns %) col-idxs))]
    (cond-> (assoc table :content [new-head new-body] :normalized? true)
      new-alignments (assoc :alignments new-alignments))))

(defn- parse-table-row-cells [line]
  (let [trimmed (str/trim line)
        content (cond-> trimmed
                  (str/starts-with? trimmed "|") (subs 1))
        content (cond-> content
                  (str/ends-with? content "|") (subs 0 (dec (count content))))]
    (mapv str/trim (str/split content #"\|"))))

(defn normalize-table-from-raw
  "Given a table node with :raw-table, parses raw cells and rebuilds
   the table AST with all columns (including extra body columns)."
  [table]
  (if-let [raw (:raw-table table)]
    (let [lines (str/split-lines raw)
          all-cells (mapv parse-table-row-cells lines)
          sep-idx (first (keep-indexed
                          (fn [i cells]
                            (when (every? #(re-matches #"\s*:?-+:?\s*" %) cells) i))
                          all-cells))
          header-cells (first all-cells)
          body-cell-rows (if sep-idx
                           (subvec all-cells (inc sep-idx))
                           (subvec all-cells 1))
          max-cols (apply max (count header-cells) (map count body-cell-rows))
          padded-header (into (vec header-cells)
                              (repeat (- max-cols (count header-cells)) ""))
          padded-body (mapv (fn [row]
                              (into (vec row)
                                    (repeat (- max-cols (count row)) "")))
                            body-cell-rows)
          sep-cells (when sep-idx (nth all-cells sep-idx))
          alignments (when sep-cells
                       (into (mapv (fn [cell]
                                     (let [t (str/trim cell)]
                                       (cond
                                         (and (str/starts-with? t ":") (str/ends-with? t ":")) "center"
                                         (str/starts-with? t ":") "left"
                                         (str/ends-with? t ":") "right"
                                         :else "none")))
                                   sep-cells)
                             (repeat (- max-cols (count sep-cells)) "none")))
          make-inline-cell (fn [type text]
                             {:type type
                              :content (if (str/blank? text)
                                         []
                                         (-> (md/parse text) :content first :content (or [])))})]
      (-> table
          (assoc :content
                 [{:type :table-head
                   :content [{:type :table-row
                              :content (mapv #(make-inline-cell :table-header %) padded-header)}]}
                  {:type :table-body
                   :content (mapv (fn [row]
                                    {:type :table-row
                                     :content (mapv #(make-inline-cell :table-data %) row)})
                                  padded-body)}])
          (assoc :alignments alignments)
          (assoc :normalized? true)
          (dissoc :raw-table)))
    table))

(defn table-filter [{:keys [col-matcher row-matcher]} nodes]
  (let [tables (collect-nodes-deep #{:table} nodes)]
    (for [table tables
          :let [table (if (:raw-table table)
                        (normalize-table-from-raw table)
                        table)
                head-row (-> table :content first :content first)
                headers (mapv #(md/node->text %) (:content head-row))
                col-idxs (if col-matcher
                           (keep-indexed (fn [i h] (when (text-matches? col-matcher h) i)) headers)
                           (range (count headers)))
                body-rows (-> table :content second :content)
                matched-rows (cond->> body-rows
                               row-matcher
                               (filter (fn [row]
                                         (some #(text-matches? row-matcher (md/node->text %))
                                               (:content row)))))]
          :when (seq col-idxs)]
      (rebuild-table table col-idxs matched-rows))))

(def ^:private complex-filter-fns
  {:section section-filter
   :list-item list-filter
   :task task-filter
   :front-matter front-matter-filter
   :table table-filter})

(defn selector->filter-fn [selector]
  (let [selector-type (:type selector)]
    (cond
      (= :section selector-type)
      (fn [nodes]
        (section-filter selector (strip-separators nodes)))

      (contains? simple-filter-specs selector-type)
      (fn [nodes]
        (let [results (simple-filter selector (strip-separators nodes))]
          (interpose result-separator results)))

      (contains? complex-filter-fns selector-type)
      (fn [nodes]
        (let [results ((complex-filter-fns selector-type) selector (strip-separators nodes))]
          (interpose result-separator results)))

      :else
      (throw (ex-info (str "Unknown selector type: " selector-type)
                      {:selector selector})))))

(defn- flatten-inline-texts
  "Returns a vector of maps with :text and :path for all :text nodes
   in depth-first order within an inline content tree."
  [content]
  (let [result (volatile! [])]
    (letfn [(walk [nodes path]
              (doseq [[i node] (map-indexed vector nodes)]
                (let [current-path (conj path i)]
                  (if (= :text (:type node))
                    (vswap! result conj {:text (:text node) :path current-path})
                    (when (:content node)
                      (walk (:content node) (conj current-path :content)))))))]
      (walk content []))
    @result))

(defn- apply-replacement-cross-boundary
  "Walk bottom-up. At each node with inline content, try to find the pattern
   in the concatenated text. If match spans multiple text nodes, redistribute."
  [node pattern replacement]
  (let [node (if (and (map? node) (:content node))
               (update node :content
                       (fn [children]
                         (mapv #(if (map? %)
                                  (apply-replacement-cross-boundary % pattern replacement)
                                  %)
                               children)))
               node)]
    (if (and (map? node) (:content node))
      (let [entries (flatten-inline-texts (:content node))
            concat-text (apply str (map :text entries))]
        (if (seq entries)
          (let [m (re-matcher pattern concat-text)]
            (if (.find m)
              (let [match-start (.start m)
                    match-end (.end m)
                    match-text (.group m)
                    positions (loop [es entries, pos 0, result []]
                                (if (empty? es)
                                  result
                                  (let [{:keys [text path]} (first es)]
                                    (recur (rest es) (+ pos (count text))
                                           (conj result {:path path :start pos
                                                         :end (+ pos (count text)) :text text})))))
                    affected (filterv (fn [{:keys [start end]}]
                                        (and (< start match-end) (> end match-start)))
                                      positions)]
                (if (<= (count affected) 1)
                  node
                  (let [repl-str (str/replace match-text pattern replacement)
                        new-texts (mapv (fn [pos-entry]
                                          (let [{:keys [start text]} pos-entry
                                                first? (= pos-entry (first affected))
                                                last? (= pos-entry (peek affected))]
                                            (cond
                                              first? (str (subs text 0 (- match-start start)) repl-str)
                                              last?  (subs text (- match-end start))
                                              :else  "")))
                                        affected)
                        new-content (reduce (fn [tree [pos-entry new-text]]
                                              (assoc-in tree (conj (:path pos-entry) :text) new-text))
                                            (vec (:content node))
                                            (map vector affected new-texts))]
                    (apply-replacement-cross-boundary (assoc node :content new-content) pattern replacement))))
              node))
          node))
      node)))


(defn apply-replacements [results selectors]
  (let [text-replacements (into [] (keep #(-> % :matcher :replace)) selectors)
        url-replacements (into [] (keep #(-> % :url-matcher :replace)) selectors)
        lang-replacements (into [] (keep #(-> % :language-matcher :replace)) selectors)
        make-xf (fn [replacements]
                  (apply comp (reverse (map (fn [{:keys [pattern replacement]}]
                                              #(str/replace % pattern replacement))
                                            replacements))))]
    (cond-> results
      (seq text-replacements)
      (->> (walk/postwalk
            (let [text-xf (make-xf text-replacements)]
              (fn [node]
                (if (and (map? node) (= :text (:type node)))
                  (update node :text text-xf)
                  node)))))

      (seq text-replacements)
      (->> (mapv (fn [node]
                   (reduce (fn [n {:keys [pattern replacement]}]
                             (apply-replacement-cross-boundary n pattern replacement))
                           node text-replacements))))

      (seq url-replacements)
      (->> (walk/postwalk
            (let [url-xf (make-xf url-replacements)]
              (fn [node]
                (cond
                  (and (map? node) (= :link (:type node)))
                  (update-in node [:attrs :href] url-xf)
                  (and (map? node) (= :image (:type node)))
                  (update-in node [:attrs :src] url-xf)
                  :else node)))))

      (seq lang-replacements)
      (->> (walk/postwalk
            (let [lang-xf (make-xf lang-replacements)]
              (fn [node]
                (if (and (map? node) (= :code (:type node)) (:language node))
                  (update node :language lang-xf)
                  node))))))))


(defn run-pipeline [nodes selector-str]
  (binding [*selector-input* selector-str]
    (let [segments (split-pipeline selector-str)
          selectors (mapv (fn [{:keys [text offset]}]
                            (binding [*selector-offset* (dec offset)]
                              (try
                                (assoc (parse-selector text) :offset offset)
                                (catch clojure.lang.ExceptionInfo e
                                  (let [{:keys [type col message]} (ex-data e)]
                                    (if (and (= :parse-error type)
                                             (> offset 1)
                                             (= col offset)
                                             (= message "expected valid query"))
                                      (throw-parse-error 1 "expected end of input or selector")
                                      (throw e)))))))
                          segments)
          result (reduce (fn [nodes sel]
                           ((selector->filter-fn sel) nodes))
                         nodes
                         selectors)]
      (apply-replacements result selectors))))

(def ^:dynamic *emit-opts* nil)

(declare emit-inline-str)

(defn ref-key-comparator
  "Comparator for reference keys that handles mixed numeric/string types.
   Numbers sort before strings, strings sort case-insensitively."
  [a b]
  (let [a-num (if (number? a) a (parse-long (str a)))
        b-num (if (number? b) b (parse-long (str b)))]
    (cond
      (and a-num b-num) (compare a-num b-num)
      a-num -1
      b-num 1
      :else (compare (str/lower-case (str a)) (str/lower-case (str b))))))

(defn detect-link-forms
  "Detects the original form of each link in the raw markdown.
   Returns a map of [text href] -> {:form :inline/:reference/:collapsed/:shortcut
                                     :ref-id :title :title-quote}"
  [raw-md ref-defs]
  (let [result (atom {})
        inline-pattern #"\[([^\]]+)\]\((\S+?)(?:\s+([\"'])(.*?)\3)?\)"
        collapsed-pattern #"\[([^\]]+)\]\[\]"
        ref-pattern #"\[([^\]]+)\]\[([^\]]+)\]"
        shortcut-pattern #"(?<!\])\[([^\]]+)\](?!\[|\(|:)"]
    ;; 1. Inline links
    (doseq [[_ text url quote title] (re-seq inline-pattern raw-md)]
      (swap! result assoc [text url]
             (cond-> {:form :inline}
               title (assoc :title title :title-quote (first quote)))))
    ;; 2. Collapsed links
    (doseq [[_ text] (re-seq collapsed-pattern raw-md)]
      (when-let [def (get ref-defs text)]
        (swap! result assoc [text (:url def)]
               (cond-> {:form :collapsed :ref-id text}
                 (:title def) (assoc :title (:title def)
                                     :title-quote (:title-quote def))))))
    ;; 3. Reference links
    (doseq [[_ text ref-id] (re-seq ref-pattern raw-md)]
      (when-let [def (get ref-defs ref-id)]
        (swap! result assoc [text (:url def)]
               (cond-> {:form :reference :ref-id ref-id}
                 (:title def) (assoc :title (:title def)
                                     :title-quote (:title-quote def))))))
    ;; 4. Shortcut links
    (doseq [[_ text] (re-seq shortcut-pattern raw-md)]
      (when-let [def (get ref-defs text)]
        (let [k [text (:url def)]]
          (when-not (get @result k)
            (swap! result assoc k
                   (cond-> {:form :shortcut :ref-id text}
                     (:title def) (assoc :title (:title def)
                                         :title-quote (:title-quote def))))))))
    @result))

(defn- emit-link-never-inline
  "Emits a link in never-inline mode. Preserves collapsed/shortcut/non-numeric reference forms.
   Inline and numeric reference links are assigned sequential numeric refs."
  [node text href link-form]
  (let [{:keys [url->ref counter refs]} *emit-opts*
        form (:form link-form)
        title (or (:title link-form) (get-in node [:attrs :title]))
        title-quote (or (:title-quote link-form) \")]
    (case form
      :collapsed (do
                   (swap! refs assoc text {:url href :title title :title-quote title-quote})
                   (str "[" text "][]"))
      :shortcut (do
                  (swap! refs assoc text {:url href :title title :title-quote title-quote})
                  (str "[" text "]"))
      :reference (let [ref-id (:ref-id link-form)
                       numeric-id (parse-long ref-id)]
                   (if numeric-id
                     ;; Numeric ref: reassign sequential
                     (let [dedup-key [href title]
                           existing (get @url->ref dedup-key)
                           ref-num (or existing
                                       (let [n (swap! counter inc)]
                                         (swap! url->ref assoc dedup-key n)
                                         n))]
                       (when-not existing
                         (swap! refs assoc ref-num {:url href :title title :title-quote title-quote}))
                       (str "[" text "][" ref-num "]"))
                     ;; Non-numeric ref: preserve original ref-id
                     (do
                       (swap! refs assoc ref-id {:url href :title title :title-quote title-quote})
                       (str "[" text "][" ref-id "]"))))
      ;; :inline or unknown — assign sequential numeric ref
      (let [dedup-key [href title]
            existing (get @url->ref dedup-key)
            ref-num (or existing
                        (let [n (swap! counter inc)]
                          (swap! url->ref assoc dedup-key n)
                          n))]
        (when-not existing
          (swap! refs assoc ref-num {:url href :title title :title-quote title-quote}))
        (str "[" text "][" ref-num "]")))))

(defn- emit-link-keep
  "Emits a link preserving its original form."
  [node text href link-form]
  (let [{:keys [refs]} *emit-opts*
        form-info (or link-form {:form :inline})
        form (:form form-info)
        title (or (:title form-info) (get-in node [:attrs :title]))
        title-quote (or (:title-quote form-info) \")]
    (case form
      :inline (if (seq title)
                (str "[" text "](" href " " title-quote title title-quote ")")
                (str "[" text "](" href ")"))
      :collapsed (do
                   (swap! refs assoc text {:url href :title title :title-quote title-quote})
                   (str "[" text "][]"))
      :shortcut (do
                  (swap! refs assoc text {:url href :title title :title-quote title-quote})
                  (str "[" text "]"))
      :reference (let [ref-id (:ref-id form-info)]
                   (swap! refs assoc ref-id {:url href :title title :title-quote title-quote})
                   (str "[" text "][" ref-id "]"))
      ;; default - inline fallback
      (if (seq title)
        (str "[" text "](" href " " title-quote title title-quote ")")
        (str "[" text "](" href ")")))))

(defn- emit-link-inline
  "Emits a link as inline, converting all forms."
  [node text href link-form]
  (let [title (or (:title (or link-form {})) (get-in node [:attrs :title]))
        title-quote (or (:title-quote (or link-form {})) \")]
    (if (seq title)
      (str "[" text "](" href " " title-quote title title-quote ")")
      (str "[" text "](" href ")"))))

(defn emit-inline [node]
  (case (:type node)
    :text (:text node)
    :softbreak "\n"
    :hardbreak "\n\n"
    :strong (str "**" (emit-inline-str (:content node)) "**")
    :em (str "_" (emit-inline-str (:content node)) "_")
    :strikethrough (str "~~" (emit-inline-str (:content node)) "~~")
    :footnote-ref (let [label (:label node)]
                    (if-let [label->num (:footnote-label->num *emit-opts*)]
                      (if (not= false (:renumber-footnotes *emit-opts*))
                        (let [num (or (get @label->num label)
                                      (let [n (swap! (:footnote-counter *emit-opts*) inc)]
                                        (swap! label->num assoc label n)
                                        n))]
                          (str "[^" num "]"))
                        (do (swap! label->num assoc label label)
                            (str "[^" label "]")))
                      (str "[^" label "]")))
    :link (let [text (emit-inline-str (:content node))
                href (get-in node [:attrs :href])
                link-format (:link-format *emit-opts*)
                link-forms (:link-forms *emit-opts*)
                link-form (when link-forms (get link-forms [text href]))]
            (case link-format
              "never-inline" (emit-link-never-inline node text href link-form)
              "keep" (emit-link-keep node text href link-form)
              "inline" (emit-link-inline node text href link-form)
              "reference" (emit-link-never-inline node text href link-form)
              ;; default
              (str "[" text "](" href ")")))
    :image (if (and *emit-opts* (contains? #{"reference" "never-inline"} (:link-format *emit-opts*)))
             (let [{:keys [url->ref counter refs]} *emit-opts*
                   src (get-in node [:attrs :src])
                   alt (emit-inline-str (:content node))
                   dedup-key [src nil]
                   ref-num (or (get @url->ref dedup-key)
                               (let [n (swap! counter inc)]
                                 (swap! url->ref assoc dedup-key n)
                                 (swap! refs assoc n {:url src})
                                 n))]
               (str "![" alt "][" ref-num "]"))
             (str "![" (emit-inline-str (:content node)) "]("
                  (get-in node [:attrs :src]) ")"))
    :monospace (str "`" (emit-inline-str (:content node)) "`")
    :formula (str "$" (emit-inline-str (:content node)) "$")
    ;; fallback — try content or text
    (if-let [content (:content node)]
      (emit-inline-str content)
      (or (:text node) ""))))

(defn extract-table-alignments [markdown-text]
  (let [lines (str/split-lines markdown-text)]
    (->> lines
         (filter #(re-matches #"\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)+\|?\s*" %))
         (mapv (fn [line]
                 (->> (str/split (str/trim line) #"\|")
                      (remove str/blank?)
                      (mapv (fn [cell]
                              (let [cell (str/trim cell)]
                                (cond
                                  (and (str/starts-with? cell ":") (str/ends-with? cell ":")) "center"
                                  (str/starts-with? cell ":") "left"
                                  (str/ends-with? cell ":") "right"
                                  :else "none"))))))))))

(defn attach-table-alignments [nodes alignments-seq]
  (:nodes
   (reduce (fn [{:keys [idx nodes]} node]
             (if (= :table (:type node))
               (let [aligns (get alignments-seq idx)]
                 {:idx (inc idx)
                  :nodes (conj nodes (if aligns
                                       (assoc node :alignments aligns)
                                       node))})
               {:idx idx
                :nodes (conj nodes node)}))
           {:idx 0 :nodes []}
           nodes)))

(defn- content-text [node]
  (str/join (keep :text (:content node))))

(defn extract-raw-tables [markdown-text]
  (let [lines (str/split-lines markdown-text)]
    (->> (map-indexed vector lines)
         (partition-by (fn [[_ line]] (str/starts-with? (str/trim line) "|")))
         (filter (fn [group] (str/starts-with? (str/trim (second (first group))) "|")))
         (mapv (fn [group] (str/join "\n" (map second group)))))))

(defn attach-raw-tables [nodes raw-tables]
  (:nodes
   (reduce (fn [{:keys [idx nodes]} node]
             (if (= :table (:type node))
               {:idx (inc idx)
                :nodes (conj nodes (if-let [raw (get raw-tables idx)]
                                     (assoc node :raw-table raw)
                                     node))}
               {:idx idx
                :nodes (conj nodes node)}))
           {:idx 0 :nodes []}
           nodes)))

(defn emit-node [node]
  (case (:type node)
    :heading (str (apply str (repeat (:heading-level node) "#")) " "
                  (emit-inline-str (:content node)))
    :paragraph (emit-inline-str (:content node))
    :plain (emit-inline-str (:content node))
    :bullet-list (str/join "\n" (map emit-node (:content node)))
    :todo-list (str/join "\n" (map emit-node (:content node)))
    :numbered-list (str/join "\n" (map-indexed
                                   (fn [i item]
                                     (let [n (+ i (or (get-in node [:attrs :start]) 1))
                                           item-with-order (assoc-in item [:attrs :order] n)]
                                       (emit-node item-with-order)))
                                   (:content node)))
    :list-item (let [order (get-in node [:attrs :order])
                     prefix (if order (str order ". ") "- ")
                     indent (apply str (repeat (count prefix) " "))
                     parts (map emit-node (:content node))
                     combined (str/join "\n\n" parts)
                     lines (str/split combined #"\n" -1)]
                 (str prefix (first lines)
                      (when (> (count lines) 1)
                        (str "\n" (str/join "\n" (map #(if (str/blank? %) "" (str indent %)) (rest lines)))))))
    :todo-item (let [order (get-in node [:attrs :order])
                     prefix (if order (str order ". ") "- ")]
                 (str prefix "[" (if (get-in node [:attrs :checked]) "x" " ") "] "
                      (str/join (str "\n" (apply str (repeat (+ (count prefix) 4) " ")))
                                (map emit-node (:content node)))))
    :blockquote (str/join "\n" (map #(str "> " (emit-node %)) (:content node)))
    :code (str "```" (or (:language node) "") "\n"
               (str/trimr (content-text node))
               "\n```")
    :ruler "---"
    :html-block (content-text node)
    :link (emit-inline (assoc node :type :link))
    :image (emit-inline (assoc node :type :image))
    :table (if (and (:raw-table node) (not (:normalized? node)))
             (:raw-table node)
             (let [[head body] (:content node)
                   head-row (first (:content head))
                   body-rows (:content body)
                   all-rows (cons head-row body-rows)
                   row-texts (mapv (fn [row]
                                     (mapv (fn [cell] (emit-inline-str (:content cell)))
                                           (:content row)))
                                   all-rows)
                   max-cols (apply max (map count row-texts))
                   row-texts (mapv (fn [texts]
                                     (into texts (repeat (- max-cols (count texts)) "")))
                                   row-texts)
                   col-widths (mapv (fn [col-idx]
                                      (max 3 (apply max (map #(count (nth % col-idx)) row-texts))))
                                    (range max-cols))
                   aligns (let [a (vec (or (:alignments node) []))]
                            (into a (repeat (- max-cols (count a)) "none")))
                   pad-right (fn [s width]
                               (let [pad (max 0 (- width (count s)))]
                                 (str s (apply str (repeat pad " ")))))
                   make-sep (fn [align width]
                              (case align
                                "center" (str ":" (apply str (repeat width "-")) ":")
                                "left" (str ":" (apply str (repeat (inc width) "-")))
                                "right" (str (apply str (repeat (inc width) "-")) ":")
                                (apply str (repeat (+ width 2) "-"))))
                   format-row (fn [texts]
                                (str "| " (str/join " | " (map pad-right texts col-widths)) " |"))
                   sep-row (str "|" (str/join "|" (map make-sep aligns col-widths)) "|")]
               (str/join "\n" (concat [(format-row (first row-texts)) sep-row]
                                      (map format-row (rest row-texts))))))
    :block-formula (str "$$\n" (emit-inline-str (:content node)) "\n$$")
    :front-matter (let [delim (if (= :toml (:format node)) "+++" "---")]
                    (str delim "\n" (:raw node) "\n" delim))
    ;; fallback
    (if-let [content (:content node)]
      (str/join "\n" (map emit-node content))
      (or (:text node) ""))))

(defn- kebab->snake [k]
  (str/replace (name k) "-" "_"))

(defn- items->json-data [items]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (reduce-kv (fn [acc k v]
                    (assoc acc (if (keyword? k) (kebab->snake k) k) v))
                  {}
                  x)
       x))
   items))

(defn- group-nodes-by-section [nodes]
  (reduce (fn [groups node]
            (if (= :heading (:type node))
              (conj groups [node])
              (if (seq groups)
                (update groups (dec (count groups)) conj node)
                (conj groups [node]))))
          []
          nodes))

(defn format-ref-definitions [refs-map]
  (when (seq refs-map)
    (str/join "\n"
              (map (fn [[n {:keys [url title title-quote]}]]
                     (if (seq title)
                       (let [q (or title-quote \")]
                         (str "[" n "]: " url " " q title q))
                       (str "[" n "]: " url)))
                   refs-map))))

(defn- footnote-label-comparator [a b]
  (let [a-num (parse-long a)
        b-num (parse-long b)]
    (cond
      (and a-num b-num) (compare a-num b-num)
      a-num -1
      b-num 1
      :else (compare (str/lower-case a) (str/lower-case b)))))

(defn- collect-footnote-refs
  "Collect footnote-ref labels from an AST node tree using safe manual walk.
   Does not use clojure.walk (which hangs on footnote AST due to circular refs)."
  [nodes]
  (let [result (volatile! #{})]
    (letfn [(walk [n]
              (when (map? n)
                (when (= :footnote-ref (:type n))
                  (vswap! result conj (:label n)))
                (when-let [children (:content n)]
                  (run! walk children))))]
      (run! walk nodes))
    @result))

(defn- collect-transitive-footnote-labels
  "Given a set of initially-referenced footnote labels and a map of label->footnote-def,
   transitively collect all footnote labels referenced within footnote bodies.
   Uses BFS with cycle detection."
  [initial-labels footnotes-by-label]
  (loop [queue (seq initial-labels)
         seen initial-labels]
    (if-not queue
      seen
      (let [label (first queue)
            fn-def (get footnotes-by-label label)
            new-refs (if fn-def
                       (set/difference (collect-footnote-refs (:content fn-def)) seen)
                       #{})]
        (recur (concat (rest queue) new-refs)
               (into seen new-refs))))))

(defn format-footnote-definitions [footnote-label->num-atom footnotes-by-label renumber?]
  (when (seq @footnote-label->num-atom)
    (let [emit-fn-body (fn [fn-def]
                         (str/join "\n\n" (map (fn [block]
                                                 (emit-inline-str (:content block)))
                                               (:content fn-def))))
          entries (loop [emitted-labels #{}
                         entries []]
                    (let [current (set (keys @footnote-label->num-atom))
                          new-labels (set/difference current emitted-labels)]
                      (if (empty? new-labels)
                        entries
                        (let [new-entries
                              (doall
                               (keep (fn [label]
                                       (when-let [fn-def (get footnotes-by-label label)]
                                         (let [num (get @footnote-label->num-atom label)
                                               body (emit-fn-body fn-def)]
                                           [label num body])))
                                     new-labels))]
                          (recur (into emitted-labels new-labels)
                                 (into entries new-entries))))))]
      (if renumber?
        (let [sorted (sort-by second entries)]
          (str/join "\n"
                    (map (fn [[_label num body]]
                           (str "[^" num "]: " body))
                         sorted)))
        (let [sorted (sort-by first footnote-label-comparator entries)]
          (str/join "\n"
                    (map (fn [[label _num body]]
                           (str "[^" label "]: " body))
                         sorted)))))))

(defn- extract-ref-links
  "Extract reference link definitions from raw markdown text."
  [markdown]
  (let [pattern #"(?m)^\[([^\]]+)\]:\s+(\S+)(?:\s+([\"'])(.*?)\3)?\s*$"]
    (->> (re-seq pattern markdown)
         (reduce (fn [m [_ ref url quote title]]
                   (assoc m ref (cond-> {:url url}
                                  (seq title) (assoc :title title
                                                     :title-quote (first quote)))))
                 (sorted-map)))))

(defn emit-markdown
  ([nodes] (emit-markdown nodes nil))
  ([nodes opts]
   (let [raw-md (:raw-md opts)
         ref-defs (when raw-md (extract-ref-links raw-md))
         link-forms (when raw-md (detect-link-forms raw-md ref-defs))
         link-format (or (:link-format opts) "never-inline")
         link-placement (or (:link-placement opts) "section")
         renumber-footnotes (get opts :renumber-footnotes true)
         groups (separate-results nodes)
         group-sep (if (:no-br opts) "\n\n" "\n\n   -----\n\n")
         footnotes (when-let [fns (:footnotes (:ast opts))]
                     (when (seq fns) fns))
         footnotes-by-label (when footnotes
                              (into {} (map (juxt :label identity)) footnotes))
         footnote-label->num (atom {})
         footnote-counter (atom 0)
         needs-refs? (contains? #{"reference" "never-inline" "keep"} link-format)]
     (if needs-refs?
       (let [counter (atom 0)
             url->ref (atom {})]
         (if (= "section" link-placement)
           ;; Section placement
           (let [refs-for-fns (atom (sorted-map-by ref-key-comparator))
                 main-output
                 (str/join group-sep
                           (mapv (fn [group]
                                   (let [section-groups (group-nodes-by-section (vec group))]
                                     (str/join "\n\n"
                                               (mapv (fn [sg]
                                                       (let [refs (atom (sorted-map-by ref-key-comparator))]
                                                         (binding [*emit-opts* {:link-format link-format
                                                                                :link-forms link-forms
                                                                                :refs refs
                                                                                :counter counter
                                                                                :url->ref url->ref
                                                                                :footnote-label->num footnote-label->num
                                                                                :footnote-counter footnote-counter
                                                                                :renumber-footnotes renumber-footnotes}]
                                                           (let [body (str/join "\n\n" (map emit-node sg))
                                                                 defs (format-ref-definitions @refs)]
                                                             (if (seq defs)
                                                               (str body "\n\n" defs)
                                                               body)))))
                                                     section-groups))))
                                 groups))]
             (binding [*emit-opts* {:link-format link-format
                                    :link-forms link-forms
                                    :refs refs-for-fns
                                    :counter counter
                                    :url->ref url->ref
                                    :footnote-label->num footnote-label->num
                                    :footnote-counter footnote-counter
                                    :renumber-footnotes renumber-footnotes}]
               (let [fn-defs (when footnotes-by-label
                               (format-footnote-definitions footnote-label->num footnotes-by-label renumber-footnotes))
                     ref-defs-from-fns (format-ref-definitions @refs-for-fns)
                     suffix (str/join "\n" (remove nil? [ref-defs-from-fns fn-defs]))]
                 (if (seq suffix)
                   (str main-output "\n\n" suffix)
                   main-output))))
           ;; Doc placement
           (let [refs (atom (sorted-map-by ref-key-comparator))]
             (binding [*emit-opts* {:link-format link-format
                                    :link-forms link-forms
                                    :refs refs
                                    :counter counter
                                    :url->ref url->ref
                                    :footnote-label->num footnote-label->num
                                    :footnote-counter footnote-counter
                                    :renumber-footnotes renumber-footnotes}]
               (let [body (str/join group-sep
                                    (mapv (fn [group]
                                            (str/join "\n\n" (map emit-node group)))
                                          groups))
                     fn-defs (when footnotes-by-label
                               (format-footnote-definitions footnote-label->num footnotes-by-label renumber-footnotes))
                     ref-defs (format-ref-definitions @refs)
                     defs-sep (if (> (count groups) 1) group-sep "\n\n")
                     suffix (str/join "\n" (remove nil? [ref-defs fn-defs]))]
                 (if (seq suffix) (str body defs-sep suffix) body))))))
       ;; Inline format
       (binding [*emit-opts* {:link-format link-format
                              :link-forms link-forms
                              :footnote-label->num footnote-label->num
                              :footnote-counter footnote-counter
                              :renumber-footnotes renumber-footnotes}]
         (let [main-output (str/join group-sep
                                     (mapv (fn [group]
                                             (str/join "\n\n" (map emit-node group)))
                                           groups))
               fn-defs (when footnotes-by-label
                         (format-footnote-definitions footnote-label->num footnotes-by-label renumber-footnotes))]
           (if (seq fn-defs)
             (str main-output "\n\n" fn-defs)
             main-output)))))))

(defn- emit-inline-str [content]
  (apply str (map emit-inline content)))

(declare nodes->items)

(defn node->item [node]
  (case (:type node)
    :heading
    {:section {:depth (:heading-level node)
               :title (emit-inline-str (:content node))}}

    (:paragraph :plain)
    {:paragraph (emit-inline-str (:content node))}

    :code
    (let [info (:info node)
          [lang metadata] (when (seq info)
                            (str/split info #"\s+" 2))]
      {:code-block (cond-> {:code (content-text node)
                            :type "code"}
                     (seq lang) (assoc :language lang)
                     (seq metadata) (assoc :metadata metadata))})

    :block-formula
    {:code-block {:code (:text node)
                  :type "math"}}

    :link
    (let [title (get-in node [:attrs :title])]
      {:link (cond-> {:display (emit-inline-str (:content node))
                      :url (get-in node [:attrs :href])}
               title (assoc :title title))})

    :image
    (let [title (get-in node [:attrs :title])]
      {:image (cond-> {:alt (emit-inline-str (:content node))
                       :url (get-in node [:attrs :src])}
                title (assoc :title title))})

    :blockquote
    {:block-quote (nodes->items (:content node))}

    :bullet-list
    {:list (mapv (fn [li] {:item (nodes->items (:content li))})
                 (:content node))}

    :numbered-list
    {:list (vec (map-indexed
                 (fn [i li]
                   {:item (nodes->items (:content li))
                    :index (+ i (or (get-in node [:attrs :start]) 1))})
                 (:content node)))}

    :todo-list
    {:list (mapv (fn [li]
                   (cond-> {:item (nodes->items (:content li))}
                     (= :todo-item (:type li))
                     (assoc :checked (boolean (get-in li [:attrs :checked])))))
                 (:content node))}

    :table
    (let [[head body] (:content node)
          emit-row (fn [row]
                     (mapv #(emit-inline-str (:content %)) (:content row)))
          head-row (first (:content head))
          col-count (count (:content head-row))
          all-rows (into [(emit-row head-row)]
                         (map emit-row (:content body)))]
      {:table {:alignments (or (:alignments node) (vec (repeat col-count "none")))
               :rows all-rows}})

    :ruler
    {:thematic-break nil}

    (:html-block :html-inline)
    {:html (content-text node)}

    :front-matter
    {:front-matter {:variant (name (:format node))
                    :body (:raw node)}}

    ;; default fallback
    (if-let [content (:content node)]
      {:paragraph (emit-inline-str content)}
      {})))

(defn nodes->items [nodes]
  (loop [remaining (seq nodes), result [], current-section nil]
    (if-not remaining
      (if current-section
        (conj result (update-in current-section [:section :body] vec))
        result)
      (let [node (first remaining)
            item (node->item node)]
        (if (:section item)
          (if (or (nil? current-section)
                  (<= (get-in item [:section :depth])
                      (get-in current-section [:section :depth])))
            ;; Same/shallower: flush current and start new
            (let [flushed (if current-section
                            (conj result (update-in current-section [:section :body] vec))
                            result)]
              (recur (next remaining) flushed
                     (assoc-in item [:section :body] [])))
            ;; Deeper: collect sub-section nodes and recurse
            (let [current-depth (get-in current-section [:section :depth])
                  [sub-nodes rest-nodes]
                  (loop [r (next remaining), collected [node]]
                    (if-not (seq r)
                      [collected nil]
                      (let [n (first r)]
                        (if (and (= :heading (:type n))
                                 (<= (:heading-level n) current-depth))
                          [collected (seq r)]
                          (recur (next r) (conj collected n))))))]
              (recur rest-nodes result
                     (update-in current-section [:section :body]
                                into (nodes->items sub-nodes)))))
          ;; Non-heading: add to body or result
          (if current-section
            (recur (next remaining) result
                   (update-in current-section [:section :body] conj item))
            (recur (next remaining) (conj result item) nil)))))))

(defn- node->plain-text [node]
  (case (:type node)
    :softbreak "\n"
    :text (:text node)
    :code (or (get-in node [:attrs :value]) "")
    (if (:content node)
      (apply str (map node->plain-text (:content node)))
      (or (:text node) ""))))

(defn- node->plain-texts [node]
  (case (:type node)
    :front-matter
    (if-let [raw (:raw node)]
      [raw]
      [])

    (:bullet-list :numbered-list :todo-list)
    (map node->plain-text (:content node))

    :code
    [(str/trimr (md/node->text node))]

    :ruler []

    :table
    (let [rows (mapcat :content (:content node))]
      (map (fn [row]
             (str/trim (str/join " " (map #(str/trim (md/node->text %)) (:content row)))))
           rows))

    [(node->plain-text node)]))

(defn- wrap-list-items
  "Wrap consecutive :list-item nodes into :bullet-list nodes."
  [nodes]
  (reduce (fn [acc node]
            (if (= :list-item (:type node))
              (let [prev (peek acc)]
                (if (and prev (= :bullet-list (:type prev)))
                  (update acc (dec (count acc)) update :content conj node)
                  (conj acc {:type :bullet-list :content [node]})))
              (conj acc node)))
          []
          nodes))

(defn- split-by-separator
  "Split nodes into groups at :result-separator boundaries."
  [nodes]
  (reduce (fn [groups node]
            (if (= :result-separator (:type node))
              (conj groups [])
              (update groups (dec (count groups)) conj node)))
          [[]]
          nodes))



(defn- build-json-footnotes [footnote-label->num-atom footnotes-by-label]
  (let [entries (loop [emitted-labels #{}
                       entries []]
                  (let [current (set (keys @footnote-label->num-atom))
                        new-labels (set/difference current emitted-labels)]
                    (if (empty? new-labels)
                      entries
                      (let [new-entries
                            (doall
                             (keep (fn [label]
                                     (when-let [fn-def (get footnotes-by-label label)]
                                       (let [num (get @footnote-label->num-atom label)
                                             body-parts (mapv (fn [block]
                                                                {:paragraph (emit-inline-str (:content block))})
                                                              (:content fn-def))]
                                         [(str num) body-parts])))
                                   new-labels))]
                        (recur (into emitted-labels new-labels)
                               (into entries new-entries))))))]
    (when (seq entries)
      (into (sorted-map) entries))))

(defn format-output [nodes opts]
  (let [output-kw (keyword (or (:output opts) "markdown"))
        output-kw (if (= :md output-kw) :markdown output-kw)]
    (case output-kw
      :markdown (emit-markdown nodes opts)
      :plain (let [texts (mapcat node->plain-texts nodes)
                   sep (if (:br opts) "\n\n" "\n")]
               (str/join sep texts))
      (let [clean-nodes (vec (remove #(= :result-separator (:type %)) nodes))
            has-selector? (some? (:selector opts))
            raw-md (:raw-md opts)
            ref-links (when raw-md (extract-ref-links raw-md))
            link-forms (when raw-md (detect-link-forms raw-md ref-links))
            json? (= :json output-kw)
            counter (atom 0)
            url->ref (atom (if ref-links
                             (reduce-kv (fn [m ref {:keys [url]}]
                                          (assoc m url ref))
                                        {} ref-links)
                             {}))
            refs (atom (sorted-map-by ref-key-comparator))
            footnote-label->num (atom {})
            footnote-counter (atom 0)
            footnotes (when-let [fns (:footnotes (:ast opts))]
                        (when (seq fns) fns))
            footnotes-by-label (when footnotes
                                 (into {} (map (juxt :label identity)) footnotes))
            make-opts (fn [] {:link-format "never-inline"
                              :link-forms link-forms
                              :counter counter :url->ref url->ref :refs refs
                              :footnote-label->num footnote-label->num
                              :footnote-counter footnote-counter
                              :renumber-footnotes true})
            items (if (and json? has-selector?)
                    (let [groups (split-by-separator nodes)]
                      (binding [*emit-opts* (make-opts)]
                        (vec (mapcat #(nodes->items (wrap-list-items %)) groups))))
                    (binding [*emit-opts* (when json? (make-opts))]
                      (nodes->items (wrap-list-items clean-nodes))))
            json-footnotes (when (and json? footnotes-by-label (seq @footnote-label->num))
                             (binding [*emit-opts* (make-opts)]
                               (build-json-footnotes footnote-label->num footnotes-by-label)))
            json-links (when (and json? (seq @refs))
                         (into (sorted-map-by ref-key-comparator)
                               (map (fn [[ref-key {:keys [url title]}]]
                                      [(str ref-key)
                                       (cond-> {:url url}
                                         title (assoc :title title))]))
                               @refs))
            items (if json?
                    (walk/postwalk
                     (fn [x]
                       (if (and (map? x) (:code-block x))
                         (update-in x [:code-block :code] str/trimr)
                         x))
                     items)
                    items)
            json-data (when json? (items->json-data items))
            json-items (if (and json? (not has-selector?))
                         [{:document json-data}]
                         json-data)]
        (case output-kw
          :json (json/generate-string
                 (cond-> {:items json-items}
                   json-footnotes (assoc :footnotes json-footnotes)
                   json-links (assoc :links json-links)
                   (and (seq ref-links) (not has-selector?) (not json-links))
                   (assoc :links ref-links))
                 {:pretty true})
          :edn (let [result (cond-> {:items items}
                              footnotes (assoc :footnotes footnotes))]
                 (with-out-str (pp/pprint result))))))))

(defn pre-process-front-matter [input]
  (let [lines (str/split-lines input)]
    (cond
      ;; YAML front matter
      (= "---" (first lines))
      (let [end-idx (->> (rest lines)
                         (keep-indexed (fn [i line] (when (= "---" line) (inc i))))
                         first)]
        (if end-idx
          {:front-matter {:format :yaml
                          :raw (str/join "\n" (subvec (vec lines) 1 end-idx))}
           :body (str/join "\n" (subvec (vec lines) (inc end-idx)))}
          {:front-matter nil :body input}))

      ;; TOML front matter
      (= "+++" (first lines))
      (let [end-idx (->> (rest lines)
                         (keep-indexed (fn [i line] (when (= "+++" line) (inc i))))
                         first)]
        (if end-idx
          {:front-matter {:format :toml
                          :raw (str/join "\n" (subvec (vec lines) 1 end-idx))}
           :body (str/join "\n" (subvec (vec lines) (inc end-idx)))}
          {:front-matter nil :body input}))

      :else
      {:front-matter nil :body input})))

;; Manual arg parsing because selectors like "- foo" start with dash
(defn- tokenize-for-wrap
  "Split text into tokens for wrapping. Links/images are atomic."
  [text]
  (re-seq #"(?:\!\[(?:[^\]]*)\]\([^\)]*\)|\[(?:[^\]]*)\]\([^\)]*\)|\[(?:[^\]]*)\]\[(?:[^\]]*)\])\S*|\S+" text))

(defn- wrap-tokens
  "Wrap tokens to fit within width. Returns seq of lines."
  [tokens width]
  (when (seq tokens)
    (loop [remaining (rest tokens)
           current-line (first tokens)
           lines []]
      (if (empty? remaining)
        (conj lines current-line)
        (let [token (first remaining)
              combined (str current-line " " token)]
          (if (<= (count combined) width)
            (recur (rest remaining) combined lines)
            (recur (rest remaining) token (conj lines current-line))))))))

(defn- detect-line-prefix
  "Detect the structural prefix of a markdown line."
  [line]
  (cond
    (re-matches #"\[\d+\]:.*" line)
    {:prefix "" :continuation "" :content line :no-wrap true}

    (str/starts-with? line "> ")
    {:prefix "> " :continuation "> " :content (subs line 2)}

    (re-matches #"^( +)- .*" line)
    (let [[_ indent] (re-matches #"^( +)- .*" line)
          prefix-len (+ (count indent) 2)
          prefix (subs line 0 prefix-len)]
      {:prefix prefix
       :continuation (apply str (repeat prefix-len \space))
       :content (subs line prefix-len)})

    (str/starts-with? line "- ")
    {:prefix "- " :continuation "  " :content (subs line 2)}

    :else
    {:prefix "" :continuation "" :content line}))

(defn- wrap-line
  "Wrap a single line respecting its prefix and the given width."
  [line width]
  (if (str/blank? line)
    line
    (let [{:keys [prefix continuation content no-wrap]} (detect-line-prefix line)]
      (if (or no-wrap (<= (count line) width))
        line
        (let [content-width (- width (count prefix))
              tokens (tokenize-for-wrap content)
              wrapped (wrap-tokens tokens content-width)]
          (str/join "\n"
                    (map-indexed (fn [i l]
                                   (str (if (zero? i) prefix continuation) l))
                                 wrapped)))))))

(defn- wrap-text
  "Wrap all lines in markdown text to the given width."
  [text width]
  (str/join "\n" (map #(wrap-line % width) (str/split text #"\n" -1))))

(defn parse-args [args]
  (loop [remaining (seq args)
         opts {}
         selector nil
         files []]
    (if-not remaining
      (cond-> opts
        selector (assoc :selector selector)
        (seq files) (assoc :files files))
      (let [arg (first remaining)]
        (cond
          (= "--" arg)
          (let [rest-args (vec (rest remaining))]
            (cond-> opts
              (and (not selector) (seq rest-args))
              (assoc :selector (first rest-args))
              (and (not selector) (> (count rest-args) 1))
              (assoc :files (into files (rest rest-args)))
              (and selector (seq rest-args))
              (assoc :files (into files rest-args))))

          (or (= "-o" arg) (= "--output" arg))
          (recur (nnext remaining) (assoc opts :output (second remaining)) selector files)

          (or (= "-q" arg) (= "--quiet" arg))
          (recur (next remaining) (assoc opts :quiet true) selector files)

          (= "--no-br" arg)
          (recur (next remaining) (assoc opts :no-br true) selector files)

          (= "--br" arg)
          (recur (next remaining) (assoc opts :br true) selector files)

          (or (= "-h" arg) (= "--help" arg))
          (recur (next remaining) (assoc opts :help true) selector files)

          (= "--link-format" arg)
          (recur (nnext remaining) (assoc opts :link-format (second remaining)) selector files)

          (contains? #{"--link-placement" "--link-pos"} arg)
          (recur (nnext remaining) (assoc opts :link-placement (second remaining)) selector files)

          (= "--renumber-footnotes" arg)
          (recur (nnext remaining) (assoc opts :renumber-footnotes (not= (second remaining) "false")) selector files)

          (= "--wrap-width" arg)
          (recur (nnext remaining) (assoc opts :wrap-width (parse-long (second remaining))) selector files)

          (= "--cwd" arg)
          (recur (nnext remaining) (assoc opts :cwd (second remaining)) selector files)

          (str/starts-with? arg "--")
          (if-let [eq-idx (str/index-of arg "=")]
            (let [flag (subs arg 0 eq-idx)
                  value (subs arg (inc eq-idx))]
              (recur (next remaining)
                     (case flag
                       "--output" (assoc opts :output value)
                       "--link-format" (assoc opts :link-format value)
                       ("--link-placement" "--link-pos") (assoc opts :link-placement value)
                       "--renumber-footnotes" (assoc opts :renumber-footnotes (not= value "false"))
                       "--wrap-width" (assoc opts :wrap-width (parse-long value))
                       "--cwd" (assoc opts :cwd value)
                       opts)
                     selector files))
            (recur (next remaining) opts selector files))

          ;; Compound short options: -oFORMAT
          (and (str/starts-with? arg "-")
               (not= arg "-")
               (> (count arg) 2)
               (not (str/starts-with? arg "--")))
          (let [flag-char (subs arg 1 2)
                value (subs arg 2)]
            (case flag-char
              "o" (recur (next remaining) (assoc opts :output value) selector files)
              (if selector
                (recur (next remaining) opts selector (conj files arg))
                (recur (next remaining) opts arg files))))

          :else
          (if selector
            (recur (next remaining) opts selector (conj files arg))
            (recur (next remaining) opts arg files)))))))


(defn process
  "Processes markdown input with given args. Returns a map with:
   :output  - the formatted output string (or nil)
   :exit    - suggested exit code (0 for success, 1 for no results)
   :error   - error string if processing failed"
  [input args]
  (let [opts (parse-args args)]
    (if (:help opts)
      {:output (str "Usage: bbg mdq [options] '<selector>'\n\n"
                    "Options:\n"
                    "  -o, --output FORMAT       Output format: markdown (default), json, edn, plain\n"
                    "  --link-format FORMAT      Link format: never-inline (default), inline, keep\n"
                    "  --link-placement PLACE    Link placement: section (default), doc\n"
                    "  -q, --quiet               Exit 0 if found, non-0 otherwise (no output)\n"
                    "  -h, --help                Show this help")
       :exit 0}
      (let [selector (:selector opts)]
        (try
          (let [{:keys [front-matter body]} (pre-process-front-matter input)
                ast (md/parse body)
                alignments (extract-table-alignments body)
                raw-tables (extract-raw-tables body)
                ast-nodes (-> (:content ast)
                              (attach-table-alignments alignments)
                              (attach-raw-tables raw-tables))
                nodes (if front-matter
                        (into [(assoc front-matter :type :front-matter)] ast-nodes)
                        ast-nodes)
                searchable-nodes (if-let [fns (seq (:footnotes ast))]
                                   (into (vec nodes) fns)
                                   nodes)
                results (if selector
                          (run-pipeline searchable-nodes selector)
                          nodes)]
            (if (:quiet opts)
              {:exit (if (seq results) 0 1)}
              (if (seq results)
                (let [output (format-output results (assoc opts :ast ast :raw-md input))]
                  {:output (if-let [w (:wrap-width opts)]
                             (wrap-text output w)
                             output)
                   :exit 0})
                {:exit 1})))
          (catch Exception e
            (let [error-data (ex-data e)]
              {:error (if (= :parse-error (:type error-data))
                        (format-pest-error error-data)
                        (str "Error: " (ex-message e)))
               :exit 1})))))))

(defn process-inputs
  "Process inputs based on args. Returns {:output :exit :error}.
   io-fns: {:read-stdin (fn [] string), :resolve-file (fn [path] string)}"
  [args {:keys [read-stdin resolve-file]}]
  (let [opts (parse-args args)
        files (:files opts)]
    (if (seq files)
      (let [stdin-used? (volatile! false)
            results (reduce (fn [acc file-path]
                              (if (= "-" file-path)
                                (if @stdin-used?
                                  acc
                                  (do (vreset! stdin-used? true)
                                      (conj acc (process (read-stdin) args))))
                                (try
                                  (conj acc (process (resolve-file file-path) args))
                                  (catch java.io.FileNotFoundException _
                                    (conj acc {:error (str "entity not found while reading file \"" file-path "\"")
                                               :exit 1})))))
                            [] files)
            errors (keep :error results)
            outputs (keep :output results)
            any-fail? (some #(pos? (:exit % 0)) results)]
        {:output (when (seq outputs) (str/join "\n" (map str/trimr outputs)))
         :error (when (seq errors) (str/join "\n" errors))
         :exit (if any-fail? 1 0)})
      (process (read-stdin) args))))

(defn exec! [args]
  (let [opts (parse-args args)
        cwd (:cwd opts)
        {:keys [output error exit]}
        (process-inputs args
                        {:read-stdin #(slurp *in*)
                         :resolve-file (fn [path]
                                         (slurp (if cwd
                                                  (java.io.File. cwd path)
                                                  (java.io.File. path))))})]
    (when output (println output))
    (when error (binding [*out* *err*] (println error)))
    (System/exit exit)))

