(ns mdq
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml]
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

(defn parse-text-matcher [s]
  (when (and s (not= s "") (not= s "*"))
    (cond
      ;; Regex replace: !s/pattern/replacement/
      (str/starts-with? s "!s/")
      (let [rest-s (subs s 3)
            ;; Find the separator between pattern and replacement
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

      ;; Quoted string (case-sensitive)
      (or (and (str/starts-with? s "\"") (str/ends-with? s "\""))
          (and (str/starts-with? s "'") (str/ends-with? s "'")))
      (let [inner (subs s 1 (dec (count s)))
            anchored-start (str/starts-with? inner "^")
            anchored-end (str/ends-with? inner "$")
            text (cond
                   (and anchored-start anchored-end) (subs inner 1 (dec (count inner)))
                   anchored-start (subs inner 1)
                   anchored-end (subs inner 0 (dec (count inner)))
                   :else inner)]
        (cond
          (and anchored-start anchored-end) (fn [s] (= s text))
          anchored-start (fn [s] (str/starts-with? s text))
          anchored-end (fn [s] (str/ends-with? s text))
          :else (fn [s] (str/includes? s text))))

      ;; Unquoted with anchors (case-insensitive)
      :else
      (let [anchored-start (str/starts-with? s "^")
            anchored-end (str/ends-with? s "$")
            text (cond
                   (and anchored-start anchored-end) (subs s 1 (dec (count s)))
                   anchored-start (subs s 1)
                   anchored-end (subs s 0 (dec (count s)))
                   :else s)
            text-lower (str/lower-case text)]
        (cond
          (and anchored-start anchored-end) (fn [s] (= (str/lower-case s) text-lower))
          anchored-start (fn [s] (str/starts-with? (str/lower-case s) text-lower))
          anchored-end (fn [s] (str/ends-with? (str/lower-case s) text-lower))
          :else (fn [s] (str/includes? (str/lower-case s) text-lower)))))))

(defn text-matches? [matcher text]
  (if (map? matcher)
    ((:match-fn matcher) text)
    (matcher text)))

(defn parse-selector [s]
  (let [s (str/trim s)]
    (cond
      ;; Section: # or ## or ### etc
      (str/starts-with? s "#")
      (let [hashes (re-find #"^#+" s)
            level (count hashes)
            text (str/trim (subs s level))]
        {:type :section
         :level level
         :matcher (parse-text-matcher text)})

      ;; Task: - [ ] unchecked, - [x] checked, - [?] any task
      (re-find #"^- \[[ x?]\]" s)
      (let [marker (subs s 2 5)  ;; "[x]" or "[ ]" or "[?]"
            task-kind (case marker
                        "[x]" :checked
                        "[ ]" :unchecked
                        "[?]" :any)
            text (str/trim (subs s 5))]
        {:type :task
         :task-kind task-kind
         :matcher (parse-text-matcher text)})

      ;; Ordered list: 1. text
      (re-find #"^\d+\." s)
      (let [text (str/trim (str/replace-first s #"^\d+\.\s*" ""))]
        {:type :list-item
         :list-kind :ordered
         :matcher (parse-text-matcher text)})

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
            ;; First word is language, rest is content matcher
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
         :matcher (parse-text-matcher alt-text)
         :url-matcher (parse-text-matcher url-text)})

      ;; Link: [display](url)
      (str/starts-with? s "[")
      (let [close-bracket (str/index-of s "](")
            display-text (when close-bracket (subs s 1 close-bracket))
            url-text (when close-bracket
                       (let [after (subs s (+ 2 close-bracket))
                             end-paren (str/last-index-of after ")")]
                         (when end-paren (subs after 0 end-paren))))]
        {:type :link
         :matcher (parse-text-matcher display-text)
         :url-matcher (parse-text-matcher url-text)})

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
            ;; Check for format specifier
            parts (str/split rest-s #"\s+" 2)
            fmt-str (first parts)
            text (second parts)
            format (case fmt-str
                     "yaml" :yaml
                     "toml" :toml
                     "" nil
                     nil nil
                     ;; If first word isn't a format, treat whole thing as text
                     nil)]
        (if (and (not format) (seq fmt-str))
          ;; No recognized format, entire rest is text matcher
          {:type :front-matter
           :format nil
           :matcher (parse-text-matcher rest-s)}
          {:type :front-matter
           :format format
           :matcher (parse-text-matcher text)}))

      ;; Table: :-: header :-: row
      (str/starts-with? s ":-:")
      (let [parts (str/split s #":-:" -1)
            ;; First element is empty (before first :-:), skip it
            meaningful (mapv str/trim (rest parts))
            col-text (first meaningful)
            row-text (second meaningful)]
        {:type :table
         :col-matcher (parse-text-matcher col-text)
         :row-matcher (parse-text-matcher row-text)})

      :else
      (throw (ex-info (str "Unknown selector: " s) {:selector s})))))

(defn slice-sections [level nodes]
  (loop [remaining nodes, sections [], current nil]
    (if-not (seq remaining)
      (if current (conj sections current) sections)
      (let [node (first remaining)]
        (if (and (= :heading (:type node))
                 (<= (:heading-level node) level))
          (recur (rest remaining)
                 (if current (conj sections current) sections)
                 {:heading node :body []})
          (if current
            (recur (rest remaining) sections
                   (update current :body conj node))
            ;; Skip nodes before first matching heading
            (recur (rest remaining) sections nil)))))))

(defn section-filter [{:keys [level matcher]} nodes]
  (let [sections (slice-sections level nodes)]
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
  (let [html-blocks (collect-nodes-deep #{:html-block} nodes)]
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
                  :content (mapv select-cols matched-rows)}]
    (assoc table :content [new-head new-body])))

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

(defn emit-inline [node]
  (case (:type node)
    :text (:text node)
    :softbreak "\n"
    :hardbreak "\n\n"
    :strong (str "**" (apply str (map emit-inline (:content node))) "**")
    :em (str "*" (apply str (map emit-inline (:content node))) "*")
    :strikethrough (str "~~" (apply str (map emit-inline (:content node))) "~~")
    :link (str "[" (apply str (map emit-inline (:content node))) "](" (get-in node [:attrs :href]) ")")
    :image (str "![" (apply str (map emit-inline (:content node))) "](" (get-in node [:attrs :src]) ")")
    :monospace (str "`" (apply str (map emit-inline (:content node))) "`")
    :formula (str "$" (apply str (map emit-inline (:content node))) "$")
    ;; fallback — try content or text
    (if-let [content (:content node)]
      (apply str (map emit-inline content))
      (or (:text node) ""))))

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
                 separator (str "|" (str/join "|" (repeat (count (:content head-row)) " --- ")) "|")]
             (str/join "\n" (concat [(emit-row head-row) separator]
                                    (map emit-row (:content body)))))
    :block-formula (str "$$\n" (apply str (map emit-inline (:content node))) "\n$$")
    :front-matter (str "---\n" (:raw node) "\n---")
    ;; fallback
    (if-let [content (:content node)]
      (str/join "\n" (map emit-node content))
      (or (:text node) ""))))

(defn emit-markdown [nodes]
  (str/join "\n\n" (map emit-node nodes)))

(defn nodes->data [nodes]
  (walk/postwalk
   (fn [x]
     (if (fn? x) "<function>" x))
   (vec nodes)))

(defn format-output [nodes opts]
  (case (keyword (or (:output opts) "markdown"))
    :markdown (emit-markdown nodes)
    :json (json/generate-string {:items (nodes->data nodes)} {:pretty true})
    :edn (with-out-str (pp/pprint {:items (nodes->data nodes)}))))

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

          :else
          (assoc opts :selector (str/join " " remaining)))))))

(defn exec! [args]
  (let [opts (parse-args args)]
    (when (:help opts)
      (println "Usage: bbg mdq [options] '<selector>'")
      (println)
      (println "Options:")
      (println "  -o, --output FORMAT  Output format: markdown (default), json, edn")
      (println "  -q, --quiet          Exit 0 if found, non-0 otherwise (no output)")
      (println "  -h, --help           Show this help")
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
              nodes (if front-matter
                      (into [(assoc front-matter :type :front-matter)] (:content ast))
                      (:content ast))
              results (run-pipeline nodes selector)]
          (if (:quiet opts)
            (System/exit (if (seq results) 0 1))
            (when (seq results)
              (println (format-output results opts)))))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "Error: " (ex-message e))))
          (System/exit 1))))))
