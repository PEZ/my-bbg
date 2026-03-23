(ns mdq
  (:require [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [nextjournal.markdown :as md]))

(defn split-pipeline [s]
  (loop [chars (seq s), current [], segments [], in-regex false, in-quote nil]
    (if-not chars
      (let [seg (str/trim (apply str current))]
        (cond-> segments
          (seq seg) (conj seg)))
      (let [c (first chars)]
        (cond
          (and (= c \/) (not in-quote))
          (recur (next chars) (conj current c) segments (not in-regex) in-quote)

          (and (contains? #{\' \"} c) (not in-regex))
          (recur (next chars) (conj current c) segments in-regex
                 (if (= in-quote c) nil c))

          (and (= c \|) (not in-regex) (not in-quote))
          (recur (next chars) [] (conj segments (str/trim (apply str current)))
                 false nil)

          :else
          (recur (next chars) (conj current c) segments in-regex in-quote))))))

(defn process-escape-sequences [s]
  (let [sb (StringBuilder.)
        chars (vec s)
        len (count chars)]
    (loop [i 0]
      (if (>= i len)
        (.toString sb)
        (if (and (= \\ (nth chars i)) (< (inc i) len))
          (let [next-c (nth chars (inc i))]
            (case next-c
              \' (do (.append sb \') (recur (+ i 2)))
              \" (do (.append sb \") (recur (+ i 2)))
              \` (do (.append sb \') (recur (+ i 2)))
              \\ (do (.append sb \\) (recur (+ i 2)))
              \n (do (.append sb \newline) (recur (+ i 2)))
              \r (do (.append sb \return) (recur (+ i 2)))
              \t (do (.append sb \tab) (recur (+ i 2)))
              \u (if (and (< (+ i 2) len) (= \{ (nth chars (+ i 2))))
                   (let [close-idx (loop [j (+ i 3)]
                                     (cond
                                       (>= j len) nil
                                       (= \} (nth chars j)) j
                                       :else (recur (inc j))))]
                     (if close-idx
                       (let [hex-str (subs s (+ i 3) close-idx)
                             code-point (Integer/parseInt hex-str 16)]
                         (.appendCodePoint sb code-point)
                         (recur (inc close-idx)))
                       (do (.append sb \\) (.append sb next-c) (recur (+ i 2)))))
                   (do (.append sb \\) (.append sb next-c) (recur (+ i 2))))
              (do (.append sb \\) (.append sb next-c) (recur (+ i 2)))))
          (do (.append sb (nth chars i)) (recur (inc i))))))))

(defn parse-text-matcher [s]
  (when (and s (not= s "") (not= s "*"))
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
            pattern (re-pattern pattern-str)]
        {:match-fn (fn [text] (re-find pattern text))
         :replace {:pattern pattern
                   :replacement replacement}})

      ;; Regex: /pattern/
      (and (str/starts-with? s "/") (str/ends-with? s "/"))
      (let [pattern (re-pattern (subs s 1 (dec (count s))))]
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
                           (when (and (contains? #{\' \"} fc)
                                      (= fc (last s1)))
                             fc)))
            quoted? (some? quote-char)
            inner (if quoted?
                    (process-escape-sequences (subs s1 1 (dec (count s1))))
                    s1)]
        (if quoted?
          ;; Quoted: case-sensitive
          (cond
            (and anchored-start anchored-end) (fn [s] (= s inner))
            anchored-start (fn [s] (str/starts-with? s inner))
            anchored-end (fn [s] (str/ends-with? s inner))
            :else (fn [s] (str/includes? s inner)))
          ;; Unquoted: case-insensitive
          (let [text-lower (str/lower-case inner)]
            (cond
              (and anchored-start anchored-end) (fn [s] (= (str/lower-case s) text-lower))
              anchored-start (fn [s] (str/starts-with? (str/lower-case s) text-lower))
              anchored-end (fn [s] (str/ends-with? (str/lower-case s) text-lower))
              :else (fn [s] (str/includes? (str/lower-case s) text-lower)))))))))

(defn text-matches? [matcher text]
  (if (map? matcher)
    ((:match-fn matcher) text)
    (matcher text)))

(defn parse-selector [s]
  (let [s (str/trim s)]
    (cond
      ;; Section: # or ## or ### etc, or #{2,4} range syntax
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
                text (str/trim rest-text)]
            {:type :section
             :level-range level-range
             :matcher (parse-text-matcher text)})
          (let [hashes (re-find #"^#+" s)
                level (count hashes)
                text (str/trim (subs s level))]
            {:type :section
             :level level
             :matcher (parse-text-matcher text)})))

      ;; Task: - [ ] unchecked, - [x] checked, - [?] any task
      (re-find #"^- \[[ x?]\]" s)
      (let [marker (subs s 2 5)
            task-kind (case marker
                        "[x]" :checked
                        "[ ]" :unchecked
                        "[?]" :any)
            text (str/trim (subs s 5))]
        {:type :task
         :task-kind task-kind
         :matcher (parse-text-matcher text)})

      ;; Ordered list: 1. text (with optional task syntax)
      (re-find #"^\d+\." s)
      (let [text (str/trim (str/replace-first s #"^\d+\.\s*" ""))
            task-match (re-find #"^\[[ x?]\]" text)]
        (if task-match
          (let [marker (subs text 0 3)
                task-kind (case marker
                            "[x]" :checked
                            "[ ]" :unchecked
                            "[?]" :any)
                rest-text (str/trim (subs text 3))]
            {:type :task
             :task-kind task-kind
             :list-kind :ordered
             :matcher (parse-text-matcher rest-text)})
          {:type :list-item
           :list-kind :ordered
           :matcher (parse-text-matcher text)}))

      ;; Unordered list: - text (but not - [ ] which is handled above)
      (str/starts-with? s "- ")
      (let [text (str/trim (subs s 2))]
        {:type :list-item
         :list-kind :unordered
         :matcher (parse-text-matcher text)})

      ;; Just "-" with no text
      (= s "-")
      {:type :list-item
       :list-kind :unordered
       :matcher nil}

      ;; Blockquote: > text
      (str/starts-with? s ">")
      (let [text (str/trim (subs s 1))]
        {:type :blockquote
         :matcher (parse-text-matcher text)})

      ;; Code block: ```language text
      (str/starts-with? s "```")
      (let [rest-s (str/trim (subs s 3))
            parts (str/split rest-s #"\s+" 2)
            lang (first parts)
            text (second parts)]
        {:type :code
         :language-matcher (parse-text-matcher lang)
         :matcher (parse-text-matcher text)})

      ;; Image: ![alt](url) — must come before link
      (str/starts-with? s "![")
      (let [close-bracket (str/index-of s "](")
            alt-text (when close-bracket (subs s 2 close-bracket))
            url-text (when close-bracket
                       (let [after (subs s (+ 2 close-bracket))
                             end-paren (str/last-index-of after ")")]
                         (when end-paren (subs after 0 end-paren))))]
        {:type :image
         :matcher (parse-text-matcher (some-> alt-text str/trim))
         :url-matcher (parse-text-matcher (some-> url-text str/trim))})

      ;; Link: [display](url)
      (str/starts-with? s "[")
      (let [close-bracket (str/index-of s "](")
            display-text (when close-bracket (subs s 1 close-bracket))
            url-text (when close-bracket
                       (let [after (subs s (+ 2 close-bracket))
                             end-paren (str/last-index-of after ")")]
                         (when end-paren (subs after 0 end-paren))))]
        {:type :link
         :matcher (parse-text-matcher (some-> display-text str/trim))
         :url-matcher (parse-text-matcher (some-> url-text str/trim))})

      ;; HTML: </> tag
      (str/starts-with? s "</>")
      (let [text (str/trim (subs s 3))]
        {:type :html
         :matcher (parse-text-matcher text)})

      ;; Paragraph: P: text
      (str/starts-with? s "P:")
      (let [text (str/trim (subs s 2))]
        {:type :paragraph
         :matcher (parse-text-matcher text)})

      ;; Front matter: +++ or +++yaml or +++toml
      (str/starts-with? s "+++")
      (let [rest-s (subs s 3)
            parts (str/split rest-s #"\s+" 2)
            fmt-str (first parts)
            text (second parts)
            format (case fmt-str
                     "yaml" :yaml
                     "toml" :toml
                     "" nil
                     nil nil
                     nil)]
        (if (and (not format) (seq fmt-str))
          {:type :front-matter
           :format nil
           :matcher (parse-text-matcher rest-s)}
          {:type :front-matter
           :format format
           :matcher (parse-text-matcher text)}))

      ;; Table: :-: header :-: row
      (str/starts-with? s ":-:")
      (let [parts (str/split s #":-:" -1)
            meaningful (mapv str/trim (rest parts))
            col-text (first meaningful)
            row-text (second meaningful)]
        {:type :table
         :col-matcher (parse-text-matcher col-text)
         :row-matcher (parse-text-matcher row-text)})

      :else
      (throw (ex-info (str "Unknown selector: " s) {:selector s})))))

(defn slice-sections [{:keys [level level-range]} nodes]
  (let [matches-heading? (if level-range
                           (fn [node]
                             (let [[lo hi] level-range]
                               (and (= :heading (:type node))
                                    (<= lo (:heading-level node) hi))))
                           (fn [node]
                             (and (= :heading (:type node))
                                  (<= (:heading-level node) level))))]
    (loop [remaining nodes, sections [], current nil]
      (if-not (seq remaining)
        (if current (conj sections current) sections)
        (let [node (first remaining)]
          (if (matches-heading? node)
            (recur (rest remaining)
                   (if current (conj sections current) sections)
                   {:heading node :body []})
            (if current
              (recur (rest remaining) sections
                     (update current :body conj node))
              (recur (rest remaining) sections nil))))))))

(defn section-filter [{:keys [matcher] :as selector} nodes]
  (let [sections (slice-sections selector nodes)]
    (->> sections
         (filter (fn [{:keys [heading]}]
                   (if matcher
                     (text-matches? matcher (md/node->text heading))
                     true)))
         (mapcat (fn [{:keys [heading body]}]
                   (cons heading body))))))

(defn collect-nodes-deep [types nodes]
  (let [results (atom [])]
    (walk/postwalk
     (fn [node]
       (when (and (map? node) (contains? types (:type node)))
         (swap! results conj node))
       node)
     nodes)
    @results))

(defn list-filter [{:keys [list-kind matcher]} nodes]
  (let [type-set (case list-kind
                   :unordered #{:bullet-list}
                   :ordered #{:numbered-list}
                   #{:bullet-list :numbered-list})
        all-lists (collect-nodes-deep type-set nodes)
        items (->> all-lists
                   (mapcat (fn [list-node]
                             (let [ordered? (= :numbered-list (:type list-node))
                                   start (or (get-in list-node [:attrs :start]) 1)]
                               (map-indexed
                                (fn [i item]
                                  (if ordered?
                                    (assoc-in item [:attrs :order] (+ start i))
                                    item))
                                (:content list-node)))))
                   (filter #(= :list-item (:type %))))]
    (cond->> items
      matcher (filter #(text-matches? matcher (md/node->text %))))))

(defn task-filter [{:keys [task-kind matcher]} nodes]
  (let [todo-lists (collect-nodes-deep #{:todo-list} nodes)
        items (->> todo-lists
                   (mapcat :content)
                   (filter #(= :todo-item (:type %))))]
    (cond->> items
      (= :unchecked task-kind) (filter #(not (get-in % [:attrs :checked])))
      (= :checked task-kind) (filter #(get-in % [:attrs :checked]))
      matcher (filter #(text-matches? matcher (md/node->text %))))))

(defn blockquote-filter [{:keys [matcher]} nodes]
  (let [bqs (collect-nodes-deep #{:blockquote} nodes)]
    (cond->> bqs
      matcher (filter #(text-matches? matcher (md/node->text %))))))

(defn code-filter [{:keys [language-matcher matcher]} nodes]
  (let [blocks (collect-nodes-deep #{:code} nodes)]
    (cond->> blocks
      language-matcher (filter #(text-matches? language-matcher (or (:language %) "")))
      matcher (filter #(text-matches? matcher (apply str (map :text (:content %))))))))

(defn paragraph-filter [{:keys [matcher]} nodes]
  (let [paras (collect-nodes-deep #{:paragraph} nodes)]
    (cond->> paras
      matcher (filter #(text-matches? matcher (md/node->text %))))))

(defn link-filter [{:keys [matcher url-matcher]} nodes]
  (let [links (collect-nodes-deep #{:link} nodes)]
    (cond->> links
      matcher (filter #(text-matches? matcher (md/node->text %)))
      url-matcher (filter #(text-matches? url-matcher (get-in % [:attrs :href] ""))))))

(defn image-filter [{:keys [matcher url-matcher]} nodes]
  (let [images (collect-nodes-deep #{:image} nodes)]
    (cond->> images
      matcher (filter #(text-matches? matcher (md/node->text %)))
      url-matcher (filter #(text-matches? url-matcher (get-in % [:attrs :src] ""))))))

(defn html-filter [{:keys [matcher]} nodes]
  (let [html-blocks (collect-nodes-deep #{:html-block :html-inline} nodes)]
    (cond->> html-blocks
      matcher (filter #(text-matches? matcher (apply str (map :text (:content %))))))))

(defn front-matter-filter [{:keys [format matcher]} nodes]
  (let [fm-nodes (filter #(= :front-matter (:type %)) nodes)]
    (cond->> fm-nodes
      format (filter #(= format (:format %)))
      matcher (filter #(text-matches? matcher (or (:raw %) ""))))))

(defn rebuild-table [table col-idxs matched-rows]
  (let [select-cols (fn [row]
                      (let [cells (:content row)]
                        (assoc row :content (mapv #(nth cells %) col-idxs))))
        head (first (:content table))
        head-row (first (:content head))
        new-head {:type (:type head)
                  :content [(select-cols head-row)]}
        body (second (:content table))
        new-body {:type (:type body)
                  :content (mapv select-cols matched-rows)}
        new-alignments (when-let [aligns (:alignments table)]
                         (mapv #(nth aligns %) col-idxs))]
    (cond-> (assoc table :content [new-head new-body])
      new-alignments (assoc :alignments new-alignments))))

(defn table-filter [{:keys [col-matcher row-matcher]} nodes]
  (let [tables (collect-nodes-deep #{:table} nodes)]
    (for [table tables
          :let [head-row (-> table :content first :content first)
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

(defn selector->filter-fn [selector]
  (case (:type selector)
    :section (fn [nodes] (section-filter selector nodes))
    :list-item (fn [nodes] (list-filter selector nodes))
    :task (fn [nodes] (task-filter selector nodes))
    :blockquote (fn [nodes] (blockquote-filter selector nodes))
    :code (fn [nodes] (code-filter selector nodes))
    :paragraph (fn [nodes] (paragraph-filter selector nodes))
    :link (fn [nodes] (link-filter selector nodes))
    :image (fn [nodes] (image-filter selector nodes))
    :html (fn [nodes] (html-filter selector nodes))
    :front-matter (fn [nodes] (front-matter-filter selector nodes))
    :table (fn [nodes] (table-filter selector nodes))
    (throw (ex-info (str "Unknown selector type: " (:type selector))
                    {:selector selector}))))

(defn apply-replacements [results selectors]
  (let [replacements (->> selectors
                          (map :matcher)
                          (filter map?)
                          (map :replace)
                          (filter some?))]
    (if (empty? replacements)
      results
      (reduce (fn [nodes {:keys [pattern replacement]}]
                (walk/postwalk
                 (fn [node]
                   (if (and (map? node) (= :text (:type node)))
                     (update node :text #(str/replace % pattern replacement))
                     node))
                 nodes))
              results
              replacements))))

(defn run-pipeline [nodes selector-str]
  (let [segments (split-pipeline selector-str)
        selectors (map parse-selector segments)
        result (reduce (fn [nodes sel]
                         ((selector->filter-fn sel) nodes))
                       nodes
                       selectors)]
    (apply-replacements result selectors)))

(def ^:dynamic *emit-opts* nil)

(defn emit-inline [node]
  (case (:type node)
    :text (:text node)
    :softbreak "\n"
    :hardbreak "\n\n"
    :strong (str "**" (apply str (map emit-inline (:content node))) "**")
    :em (str "*" (apply str (map emit-inline (:content node))) "*")
    :strikethrough (str "~~" (apply str (map emit-inline (:content node))) "~~")
    :link (if (and *emit-opts* (= "reference" (:link-format *emit-opts*)))
            (let [href (get-in node [:attrs :href])
                  text (apply str (map emit-inline (:content node)))
                  url->ref (:url->ref *emit-opts*)
                  counter (:counter *emit-opts*)
                  refs-atom (:refs *emit-opts*)
                  ref-num (or (get @url->ref href)
                              (let [n (swap! counter inc)]
                                (swap! url->ref assoc href n)
                                (swap! refs-atom assoc n {:url href
                                                          :title (get-in node [:attrs :title])})
                                n))]
              (str "[" text "][" ref-num "]"))
            (str "[" (apply str (map emit-inline (:content node))) "]("
                 (get-in node [:attrs :href]) ")"))
    :image (str "![" (apply str (map emit-inline (:content node))) "](" (get-in node [:attrs :src]) ")")
    :monospace (str "`" (apply str (map emit-inline (:content node))) "`")
    :formula (str "$" (apply str (map emit-inline (:content node))) "$")
    ;; fallback — try content or text
    (if-let [content (:content node)]
      (apply str (map emit-inline content))
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
  (let [idx (atom 0)]
    (mapv (fn [node]
            (if (= :table (:type node))
              (let [i @idx
                    aligns (get alignments-seq i)]
                (swap! idx inc)
                (if aligns
                  (assoc node :alignments aligns)
                  node))
              node))
          nodes)))

(defn- alignment->separator [a]
  (case a
    "left" ":---"
    "right" "---:"
    "center" ":---:"
    "---"))

(defn emit-node [node]
  (case (:type node)
    :heading (str (apply str (repeat (:heading-level node) "#")) " "
                  (apply str (map emit-inline (:content node))))
    :paragraph (apply str (map emit-inline (:content node)))
    :plain (apply str (map emit-inline (:content node)))
    :bullet-list (str/join "\n" (map emit-node (:content node)))
    :todo-list (str/join "\n" (map emit-node (:content node)))
    :numbered-list (str/join "\n" (map-indexed
                                   (fn [i item]
                                     (let [n (+ i (or (get-in node [:attrs :start]) 1))
                                           item-with-order (assoc-in item [:attrs :order] n)]
                                       (emit-node item-with-order)))
                                   (:content node)))
    :list-item (let [order (get-in node [:attrs :order])
                     prefix (if order (str order ". ") "- ")]
                 (str prefix (str/join (str "\n" (apply str (repeat (count prefix) " ")))
                                       (map emit-node (:content node)))))
    :todo-item (str "- [" (if (get-in node [:attrs :checked]) "x" " ") "] "
                    (str/join "\n  " (map emit-node (:content node))))
    :blockquote (str/join "\n" (map #(str "> " (emit-node %)) (:content node)))
    :code (str "```" (or (:language node) "") "\n"
               (apply str (map #(or (:text %) "") (:content node)))
               "\n```")
    :ruler "---"
    :html-block (apply str (map #(or (:text %) "") (:content node)))
    :link (str "[" (apply str (map emit-inline (:content node))) "](" (get-in node [:attrs :href]) ")")
    :image (str "![" (apply str (map emit-inline (:content node))) "](" (get-in node [:attrs :src]) ")")
    :table (let [head (first (:content node))
                 body (second (:content node))
                 emit-row (fn [row]
                            (str "| " (str/join " | " (map #(apply str (map emit-inline (:content %)))
                                                           (:content row))) " |"))
                 head-row (first (:content head))
                 col-count (count (:content head-row))
                 aligns (or (:alignments node) (repeat col-count "none"))
                 separator (str "| " (str/join " | " (map alignment->separator aligns)) " |")]
             (str/join "\n" (concat [(emit-row head-row) separator]
                                    (map emit-row (:content body)))))
    :block-formula (str "$$\n" (apply str (map emit-inline (:content node))) "\n$$")
    :front-matter (str "---\n" (:raw node) "\n---")
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
       (into {} (map (fn [[k v]] [(if (keyword? k) (kebab->snake k) k) v]) x))
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
              (map (fn [[n {:keys [url title]}]]
                     (if (seq title)
                       (str "[" n "]: " url " \"" title "\"")
                       (str "[" n "]: " url)))
                   (sort-by key refs-map)))))

(defn emit-markdown
  ([nodes] (emit-markdown nodes nil))
  ([nodes opts]
   (let [link-format (or (:link-format opts) "reference")
         link-placement (or (:link-placement opts) "section")]
     (if (= "reference" link-format)
       (let [counter (atom 0)
             url->ref (atom {})]
         (if (= "section" link-placement)
           ;; Section placement: refs after each section group
           (str/join "\n\n---\n\n"
                     (mapv (fn [group]
                             (let [refs (atom (sorted-map))]
                               (binding [*emit-opts* {:link-format "reference"
                                                      :refs refs
                                                      :counter counter
                                                      :url->ref url->ref}]
                                 (let [body (str/join "\n\n---\n\n" (map emit-node group))
                                       defs (format-ref-definitions @refs)]
                                   (if (seq defs)
                                     (str body "\n\n" defs)
                                     body)))))
                           (group-nodes-by-section nodes)))
           ;; Doc placement: all refs at end
           (let [refs (atom (sorted-map))]
             (binding [*emit-opts* {:link-format "reference"
                                    :refs refs
                                    :counter counter
                                    :url->ref url->ref}]
               (let [body (str/join "\n\n---\n\n" (map emit-node nodes))
                     defs (format-ref-definitions @refs)]
                 (if (seq defs) (str body "\n\n" defs) body))))))
       ;; Inline format
       (str/join "\n\n---\n\n" (map emit-node nodes))))))

(defn- emit-inline-str [content]
  (apply str (map emit-inline content)))

(defn- content-text [node]
  (apply str (map #(or (:text %) "") (:content node))))

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
                            (let [parts (str/split info #"\s+" 2)]
                              [(first parts) (second parts)]))]
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
    (let [head (first (:content node))
          body (second (:content node))
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
    {:html (apply str (map #(or (:text %) "") (:content node)))}

    :front-matter
    {:front-matter {:format (name (:format node))
                    :content (:raw node)}}

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

(defn format-output [nodes opts]
  (let [items (nodes->items nodes)
        footnotes (when-let [fns (:footnotes (:ast opts))]
                    (when (seq fns) fns))
        result (cond-> {:items items}
                 footnotes (assoc :footnotes footnotes))]
    (case (keyword (or (:output opts) "markdown"))
      :markdown (emit-markdown nodes opts)
      :json (json/generate-string
             (cond-> {:items (items->json-data items)}
               footnotes (assoc :footnotes (items->json-data footnotes)))
             {:pretty true})
      :edn (with-out-str (pp/pprint result))
      :plain (str/join "\n\n" (map md/node->text nodes)))))

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

;; Manual arg parsing because selectors like "- foo" start with dash,
;; which babashka.cli misinterprets as combined short flags.
;; The :else clause strips --cwd (injected by the bbg wrapper) from
;; the selector since it grabs all remaining args at once.
(defn parse-args [args]
  (loop [remaining args
         opts {}]
    (if (empty? remaining)
      opts
      (let [arg (first remaining)]
        (cond
          (= "--" arg)
          (assoc opts :selector (str/join " " (rest remaining)))

          (or (= "-o" arg) (= "--output" arg))
          (recur (drop 2 remaining) (assoc opts :output (second remaining)))

          (or (= "-q" arg) (= "--quiet" arg))
          (recur (rest remaining) (assoc opts :quiet true))

          (or (= "-h" arg) (= "--help" arg))
          (recur (rest remaining) (assoc opts :help true))

          (= "--link-format" arg)
          (recur (drop 2 remaining) (assoc opts :link-format (second remaining)))

          (= "--link-placement" arg)
          (recur (drop 2 remaining) (assoc opts :link-placement (second remaining)))

          (= "--cwd" arg)
          (recur (drop 2 remaining) opts)

          :else
          (let [selector-args (loop [r remaining, acc []]
                                (cond
                                  (empty? r) acc
                                  (= "--cwd" (first r)) (recur (drop 2 r) acc)
                                  :else (recur (rest r) (conj acc (first r)))))]
            (assoc opts :selector (str/join " " selector-args))))))))

(defn exec! [args]
  (let [opts (parse-args args)]
    (when (:help opts)
      (println "Usage: bbg mdq [options] '<selector>'")
      (println)
      (println "Options:")
      (println "  -o, --output FORMAT       Output format: markdown (default), json, edn, plain")
      (println "  --link-format FORMAT      Link format: reference (default), inline")
      (println "  --link-placement PLACE    Link placement: section (default), doc")
      (println "  -q, --quiet               Exit 0 if found, non-0 otherwise (no output)")
      (println "  -h, --help                Show this help")
      (System/exit 0))
    (let [selector (:selector opts)
          _ (when-not selector
              (binding [*out* *err*]
                (println "Error: no selector provided"))
              (System/exit 1))]
      (try
        (let [input (slurp *in*)
              {:keys [front-matter body]} (pre-process-front-matter input)
              ast (md/parse body)
              alignments (extract-table-alignments body)
              ast-nodes (attach-table-alignments (:content ast) alignments)
              nodes (if front-matter
                      (into [(assoc front-matter :type :front-matter)] ast-nodes)
                      ast-nodes)
              results (run-pipeline nodes selector)]
          (if (:quiet opts)
            (System/exit (if (seq results) 0 1))
            (when (seq results)
              (println (format-output results (assoc opts :ast ast))))))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "Error: " (ex-message e))))
          (System/exit 1))))))
