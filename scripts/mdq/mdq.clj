(ns mdq
  (:require [babashka.cli :as cli]
            [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [instaparse.core :as insta]
            [nextjournal.markdown :as md]))

(defn- format-pest-error [{:keys [col end-col message input pointer-style]}]
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

(defn- throw-parse-error [ctx col message & {:keys [end-col pointer-style]}]
  (let [offset (get ctx :parse/offset 0)
        absolute-col (+ offset col)
        absolute-end-col (when end-col (+ offset end-col))]
    (throw (ex-info message
                    (cond-> {:type :parse-error
                             :col absolute-col
                             :message message
                             :input (:parse/input ctx)}
                      absolute-end-col (assoc :end-col absolute-end-col)
                      pointer-style (assoc :pointer-style pointer-style))))))

(defn- split-pipeline [s]
  (letfn [(segment-entry [segment-text start-offset]
            (let [trimmed (string/trim segment-text)
                  left-trim (- (count segment-text)
                               (count (string/triml segment-text)))]
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

(defn- unicode-escape-end-col [hex-start-col hex]
  (+ hex-start-col (max 0 (- (count hex) 2))))

(defn- parse-unicode-escape-sequence [ctx s start-col idx valid-escape-message]
  (let [open-brace-idx (+ idx 2)
        open-brace (nth s open-brace-idx nil)
        escaped-col (+ start-col (inc idx))]
    (when (not= open-brace \{)
      (throw-parse-error ctx escaped-col valid-escape-message))
    (let [hex-start-idx (+ idx 3)
          hex-start-col (+ start-col hex-start-idx)]
      (loop [scan-idx hex-start-idx
             hex-chars []]
        (let [current (nth s scan-idx nil)]
          (cond
            (nil? current)
            (throw-parse-error ctx escaped-col valid-escape-message)

            (= current \})
            (if (empty? hex-chars)
              (throw-parse-error ctx hex-start-col "expected 1 - 6 hex characters")
              (let [hex (apply str hex-chars)]
                (if (> (count hex) 6)
                  (throw-parse-error ctx escaped-col valid-escape-message)
                  (let [code-point (Integer/parseInt hex 16)]
                    (when-not (Character/isValidCodePoint code-point)
                      (throw-parse-error ctx
                                         hex-start-col
                                         (str "invalid unicode sequence: " hex)
                                         :end-col (unicode-escape-end-col hex-start-col hex)))
                    {:next-idx (inc scan-idx)
                     :piece (String. (Character/toChars code-point))}))))

            (re-matches #"[0-9a-fA-F]" (str current))
            (recur (inc scan-idx) (conj hex-chars current))

            :else
            (throw-parse-error ctx
                               (if (empty? hex-chars)
                                 hex-start-col
                                 (+ start-col scan-idx))
                               "expected 1 - 6 hex characters")))))))

(defn- decode-escape-sequence [ctx s start-col idx valid-escape-message]
  (let [next-idx (inc idx)
        escaped (nth s next-idx nil)
        escaped-col (+ start-col next-idx)]
    (case escaped
      nil (throw-parse-error ctx escaped-col valid-escape-message)
      \" {:next-idx (+ idx 2) :piece "\""}
      \' {:next-idx (+ idx 2) :piece "'"}
      \` {:next-idx (+ idx 2) :piece "`"}
      \\ {:next-idx (+ idx 2) :piece "\\"}
      \n {:next-idx (+ idx 2) :piece "\n"}
      \r {:next-idx (+ idx 2) :piece "\r"}
      \t {:next-idx (+ idx 2) :piece "\t"}
      \u (parse-unicode-escape-sequence ctx s start-col idx valid-escape-message)
      (throw-parse-error ctx escaped-col valid-escape-message))))

(defn- process-escape-sequences
  ([ctx s]
   (process-escape-sequences ctx s 1))
  ([ctx s start-col]
   (let [valid-escape-message "expected \", ', `, \\, n, r, or t"]
     (loop [idx 0
            pieces []]
       (if (>= idx (count s))
         (apply str pieces)
         (let [ch (nth s idx)]
           (if (= ch \\)
             (let [{:keys [next-idx piece]}
                   (decode-escape-sequence ctx s start-col idx valid-escape-message)]
               (recur next-idx (conj pieces piece)))
             (recur (inc idx) (conj pieces (str ch))))))))))

(defn- validate-matcher-syntax! [ctx matcher start-col]
  (let [anchored-start (string/starts-with? matcher "^")
        anchored-end (string/ends-with? matcher "$")
        without-start (if anchored-start (subs matcher 1) matcher)
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
       (throw-parse-error ctx
              (+ candidate-col (count candidate))
                         "expected character in quoted string")

      (and (string/starts-with? candidate "/")
           (not (string/ends-with? candidate "/")))
       (throw-parse-error ctx
              (+ candidate-col (count candidate))
                         "expected regex character"))))

(defn- regex-parse-error-message [pattern description]
  (if (re-find #"\\[pP]\{[^}]*$" pattern)
    "regex parse error: Unicode escape not closed"
    (str "regex parse error: " description)))

(defn- compile-matcher-regex [ctx pattern pattern-col]
  (try
    (re-pattern pattern)
    (catch java.util.regex.PatternSyntaxException e
      (throw-parse-error ctx
                         pattern-col
                         (regex-parse-error-message pattern (.getDescription e))
                         :pointer-style :point))))

(defn- parse-regex-replace-matcher [ctx s start-col]
  (let [rest-s (subs s 3)
        sep-idx (string/index-of rest-s "/")
        pattern-str (subs rest-s 0 sep-idx)
        after-sep (subs rest-s (inc sep-idx))
        replacement (if (string/ends-with? after-sep "/")
                      (subs after-sep 0 (dec (count after-sep)))
                      after-sep)
        pattern (compile-matcher-regex ctx pattern-str (+ start-col 3))]
    {:match-fn (fn [text] (re-find pattern text))
     :replace {:pattern pattern
               :replacement replacement}}))

(defn- parse-regex-matcher [ctx s start-col]
  (let [pattern (compile-matcher-regex ctx
                                       (subs s 1 (dec (count s)))
                                       (inc start-col))]
    (fn [text] (some? (re-find pattern text)))))

(defn- strip-anchor-markers [s]
  (let [anchored-start (string/starts-with? s "^")
        anchored-end (string/ends-with? s "$")
        s1 (cond-> s
             anchored-start (subs 1)
             anchored-end (subs 0 (- (count (cond-> s anchored-start (subs 1))) 1)))]
    {:anchored-start anchored-start
     :anchored-end anchored-end
     :text (string/trim s1)}))

(defn- build-quoted-matcher [inner anchored-start anchored-end]
  (cond
    (and anchored-start anchored-end) (fn [text] (= text inner))
    anchored-start (fn [text] (string/starts-with? text inner))
    anchored-end (fn [text] (string/ends-with? text inner))
    :else (fn [text] (string/includes? text inner))))

(defn- build-unquoted-matcher [inner anchored-start anchored-end]
  (let [text-lower (string/lower-case inner)]
    (cond
      (and anchored-start anchored-end) (fn [text] (= (string/lower-case text) text-lower))
      anchored-start (fn [text] (string/starts-with? (string/lower-case text) text-lower))
      anchored-end (fn [text] (string/ends-with? (string/lower-case text) text-lower))
      :else (fn [text] (string/includes? (string/lower-case text) text-lower)))))

(defn- parse-text-matcher
  ([ctx s]
   (parse-text-matcher ctx s 1))
  ([ctx s start-col]
   (when (and s (not= s "") (not= s "*"))
     (validate-matcher-syntax! ctx s start-col)
     (cond
       (string/starts-with? s "!s/")
       (parse-regex-replace-matcher ctx s start-col)

       (and (string/starts-with? s "/") (string/ends-with? s "/"))
       (parse-regex-matcher ctx s start-col)

       :else
       (let [{:keys [anchored-start anchored-end text]} (strip-anchor-markers s)
             quote-char (when (>= (count text) 2)
                          (let [fc (first text)]
                            (when (and (or (= \" fc) (= \' fc))
                                       (= fc (last text)))
                              fc)))
             quoted? (some? quote-char)
             inner-start-col (+ start-col (if anchored-start 1 0) 1)
             inner (if quoted?
                     (process-escape-sequences ctx
                                               (subs text 1 (dec (count text)))
                                               inner-start-col)
                     text)]
         (when (and (not quoted?) (string/starts-with? inner "<"))
           (throw-parse-error ctx
                              start-col
                              "expected end of input, \"*\", unquoted string, regex, quoted string, or \"^\""))
         (if quoted?
           (build-quoted-matcher inner anchored-start anchored-end)
           (build-unquoted-matcher inner anchored-start anchored-end)))))))

(defn- text-matches? [matcher text]
  (if (map? matcher)
    ((:match-fn matcher) text)
    (matcher text)))

(def ^:private segment-grammar
  "selector = <ws?> (section-range / section / ordered-task / ordered-list
                     / task / list-item / blockquote / paragraph / code-block
                     / link / image / html / front-matter / table) <ws?>

   section-range = <'#{'> range <'}'> (<ws> text-matcher)?
   range         = #'\\d*,?\\d*'
   section       = hashes (<ws> text-matcher)?
   hashes        = #'#{1,6}'

   ordered-task  = <'1.'> <ws> <'['> task-marker <']'> (<ws> text-matcher)?
   task          = <'- ['> task-marker <']'> (<ws> text-matcher)?
   task-marker   = 'x' | ' ' | '?'

   ordered-list  = <'1.'> (<ws> text-matcher)?
   list-item     = <'-'> (<ws> list-matcher)?
   list-matcher  = !<'['> text-matcher

   blockquote    = <'>'> (<ws> text-matcher)?
   paragraph     = <'P:'> (<ws> text-matcher)?

   code-block    = <'```'> cb-body?
   cb-body       = cb-language (<ws> text-matcher)? / <ws> cb-language <ws> text-matcher / <ws> text-matcher / cb-lang-text
   cb-lang-text  = text-matcher
   cb-language   = #'[a-zA-Z0-9_+*-]+'

   link          = <'['> link-text? <']'> (<'('> link-url? <')'>)?
   image         = <'!['> link-text? <']'> (<'('> link-url? <')'>)?
   link-text     = #'[^\\]]*'
   link-url      = #'[^\\)]*'

   html          = <'</>'> (<ws> text-matcher)?

   front-matter  = <'+++'> fm-body?
   fm-body       = fm-format (<ws> text-matcher)? / <ws> text-matcher
   fm-format     = 'yaml' | 'toml'

   table         = <':-:'> <ws?> table-text table-row?
   table-row     = <':-:'> <ws?> table-text?
   table-text    = #'[^:-]+(?::[^-][^:-]*)*'

   text-matcher    = wildcard / regex-replace / regex / anchored-text
   wildcard        = '*'
   regex-replace   = <'!s/'> regex-body <'/'> replacement (<'/'> | <epsilon>)
   regex           = <'/'> regex-body <'/'>
   regex-body      = #'[^/]+'
   replacement     = #'[^/]*'

   anchored-text   = anchor-start? (quoted / unquoted) anchor-end?
   anchor-start    = <'^'>
   anchor-end      = <'$'>
   quoted          = double-quoted / single-quoted
   double-quoted   = <'\"'> dq-content <'\"'>
   single-quoted   = <\"'\"> sq-content <\"'\">
   dq-content      = #'(?:[^\"\\\\]|\\\\.)*'
   sq-content      = #\"(?:[^'\\\\\\\\]|\\\\\\\\.)*\"
   unquoted        = !<'\"'> !<\"'\"> #'.*[^$]'
   ws              = #'\\s+'")

(def ^:private seg-parser (insta/parser segment-grammar))

(defn- parse-range-str [range-str]
  (let [[_ lo-str comma hi-str] (re-find #"^(\d*)(,?)(\d*)" range-str)
        lo (when (seq lo-str) (parse-long lo-str))
        hi (when (seq hi-str) (parse-long hi-str))
        has-comma (= "," comma)]
    (cond
      (and lo (not has-comma)) [lo lo]
      (and lo hi)              [lo hi]
      (and lo has-comma)       [lo 6]
      (and has-comma hi)       [1 hi]
      :else                    nil)))

(defn- find-tree-node [tree tag]
  (when (vector? tree)
    (if (= (first tree) tag)
      tree
      (some #(find-tree-node % tag) (rest tree)))))

(defn- extract-quoted-spans [tree]
  (when (vector? tree)
    (let [tag (first tree)]
      (if (#{:dq-content :sq-content} tag)
        [{:tag tag :span (insta/span tree) :text (second tree)}]
        (mapcat extract-quoted-spans (rest tree))))))

(defn- find-quote-start-col [spans]
  (when-let [{:keys [span]} (first spans)]
    (inc (first span))))

(def ^:private segment-transform
  {:selector       (fn [inner] inner)
   :section        (fn
                     ([hashes-count] {:type :section
                                      :level (when (> hashes-count 1) hashes-count)
                                      :matcher nil})
                     ([hashes-count matcher] {:type :section
                                              :level (when (> hashes-count 1) hashes-count)
                                              :matcher matcher}))
   :hashes         count
   :section-range  (fn
                     ([range-str] {:type :section
                                   :level-range (parse-range-str range-str)
                                   :matcher nil})
                     ([range-str matcher] {:type :section
                                           :level-range (parse-range-str range-str)
                                           :matcher matcher}))
   :range          identity
   :task           (fn
                     ([task-kind] {:type :task :task-kind task-kind :matcher nil})
                     ([task-kind matcher] {:type :task :task-kind task-kind :matcher matcher}))
   :ordered-task   (fn
                     ([task-kind] {:type :task :task-kind task-kind :list-kind :ordered :matcher nil})
                     ([task-kind matcher] {:type :task :task-kind task-kind :list-kind :ordered :matcher matcher}))
   :task-marker    (fn [m] (case m "x" :checked " " :unchecked "?" :any))
   :ordered-list   (fn
                     ([] {:type :list-item :list-kind :ordered :matcher nil})
                     ([matcher] {:type :list-item :list-kind :ordered :matcher matcher}))
   :list-item      (fn
                     ([] {:type :list-item :list-kind :unordered :matcher nil})
                     ([matcher] {:type :list-item :list-kind :unordered :matcher matcher}))
   :list-matcher   identity
   :blockquote     (fn
                     ([] {:type :blockquote :matcher nil})
                     ([matcher] {:type :blockquote :matcher matcher}))
   :paragraph      (fn
                     ([] {:type :paragraph :matcher nil})
                     ([matcher] {:type :paragraph :matcher matcher}))
   :code-block     (fn
                     ([] {:type :code :language-matcher nil :matcher nil})
                     ([cb-body] cb-body))
   :cb-body        (fn
                     ([lang-or-matcher]
                      (cond
                        (string? lang-or-matcher)
                        {:type :code :language-matcher {:raw lang-or-matcher} :matcher nil}
                        (:lang-text-matcher lang-or-matcher)
                        {:type :code :language-matcher (:lang-text-matcher lang-or-matcher) :matcher nil}
                        :else
                        {:type :code :language-matcher nil :matcher lang-or-matcher}))
                     ([lang matcher]
                      {:type :code :language-matcher {:raw lang} :matcher matcher}))
   :cb-lang-text   (fn [m] {:lang-text-matcher m})
   :cb-language    identity
   :link           (fn
                     ([] {:type :link :matcher nil :url-matcher nil})
                     ([part]
                      (case (:kind part)
                        :link-url {:type :link :matcher nil :url-matcher (when (seq (:raw part)) part)}
                        :link-text {:type :link :matcher (when (seq (:raw part)) part) :url-matcher nil}))
                     ([link-text link-url]
                      {:type :link
                       :matcher (when (seq (:raw link-text)) link-text)
                       :url-matcher (when (seq (:raw link-url)) link-url)}))
   :image          (fn
                     ([] {:type :image :matcher nil :url-matcher nil})
                     ([part]
                      (case (:kind part)
                        :link-url {:type :image :matcher nil :url-matcher (when (seq (:raw part)) part)}
                        :link-text {:type :image :matcher (when (seq (:raw part)) part) :url-matcher nil}))
                     ([link-text link-url]
                      {:type :image
                       :matcher (when (seq (:raw link-text)) link-text)
                       :url-matcher (when (seq (:raw link-url)) link-url)}))
   :link-text      (fn [raw] {:raw raw :kind :link-text})
   :link-url       (fn [raw] {:raw raw :kind :link-url})
   :html           (fn
                     ([] {:type :html :matcher nil})
                     ([matcher] {:type :html :matcher matcher}))
   :front-matter   (fn
                     ([] {:type :front-matter :format nil :matcher nil})
                     ([fm-body] fm-body))
   :fm-body        (fn
                     ([fmt-or-matcher]
                      (if (keyword? fmt-or-matcher)
                        {:type :front-matter :format fmt-or-matcher :matcher nil}
                        {:type :front-matter :format nil :matcher fmt-or-matcher}))
                     ([fmt matcher]
                      {:type :front-matter :format fmt :matcher matcher}))
   :fm-format      (fn [f] (keyword f))
   :table          (fn
                     ([col-text] {:type :table :col-matcher {:raw col-text} :row-matcher nil :has-row-section false})
                     ([col-text row] {:type :table :col-matcher {:raw col-text}
                                      :row-matcher (when (string? row) {:raw row})
                                      :has-row-section true}))
   :table-row      (fn
                     ([] :no-row-text)
                     ([text] text))
   :table-text     identity
   :text-matcher   identity
   :wildcard       (fn [_] nil)
   :regex          (fn [body] {:match-type :regex :pattern body})
   :regex-replace  (fn [body replacement] {:match-type :regex-replace :pattern body :replacement replacement})
   :regex-body     identity
   :replacement    identity
   :anchored-text  (fn [& args]
                     (let [anchor-start (some #{:anchor-start} args)
                           anchor-end   (some #{:anchor-end} args)
                           text-data    (first (remove keyword? args))]
                       (assoc text-data
                              :anchored-start (boolean anchor-start)
                              :anchored-end (boolean anchor-end))))
   :anchor-start   (fn [] :anchor-start)
   :anchor-end     (fn [] :anchor-end)
   :quoted         identity
   :double-quoted  (fn [raw-content] {:match-type :quoted :quote-style :double :raw-content raw-content})
   :single-quoted  (fn [raw-content] {:match-type :quoted :quote-style :single :raw-content raw-content})
   :dq-content     identity
   :sq-content     identity
   :unquoted       (fn [text] {:match-type :unquoted :text text})})

(defn- translate-insta-error [ctx s]
  (cond
    (and (re-find #"^#{1,6}[^ {]" s)
         (not (re-find #"^#\{" s)))
    (let [hashes (re-find #"^#+" s)]
      (throw-parse-error ctx (inc (count hashes))
                         "expected end of input, space, or section options"))

    (and (string/starts-with? s "- [")
         (not (re-find #"^- \[[ x?]\]" s)))
    (throw-parse-error ctx 4 "expected \"[x]\", \"[x]\", or \"[?]\"")

    (and (re-find #"\"" s)
         (not (re-find #"\"(?:[^\"\\]|\\.)*\"" s)))
    (throw-parse-error ctx (inc (count s)) "expected character in quoted string")

    (and (re-find #"'" s)
         (not (re-find #"'(?:[^'\\]|\\.)*'" s)))
    (throw-parse-error ctx (inc (count s)) "expected character in quoted string")

    (and (re-find #"\]\(" s)
         (not (re-find #"\]\([^)]*\)" s)))
    (throw-parse-error ctx (inc (count s)) "expected \"$\"")

    (and (string/starts-with? s "+++")
         (not (re-find #"^\+\+\+(yaml|toml|$|\s)" s)))
    (let [rest-s (subs s 3)
          token-end (or (string/index-of rest-s " ") (count rest-s))
          fmt-str (subs rest-s 0 token-end)]
      (throw-parse-error ctx 4
                         (str "front matter language must be \"toml\" or \"yaml\". Found \"" fmt-str "\".")
                         :end-col (+ 3 (count fmt-str))
                         :pointer-style :tight-range))

    :else
    (throw-parse-error ctx 1 "expected valid query")))

(defn- resolve-text-matcher
  [ctx matcher-data start-col]
  (when matcher-data
    (case (:match-type matcher-data)
      :unquoted
      (let [{:keys [text anchored-start anchored-end]} matcher-data]
        (cond
          (or (string/starts-with? text "<")
              (string/starts-with? text "$"))
          (throw-parse-error ctx start-col
                             "expected end of input, \"*\", unquoted string, regex, quoted string, or \"^\"")

          (and (string/starts-with? text "/")
               (not (string/ends-with? text "/")))
          (throw-parse-error ctx (+ start-col (count text))
                             "expected regex character"))
        (build-unquoted-matcher text anchored-start anchored-end))

      :quoted
      (let [{:keys [raw-content anchored-start anchored-end]} matcher-data
            inner (process-escape-sequences ctx raw-content start-col)]
        (build-quoted-matcher inner anchored-start anchored-end))

      :regex
      (let [pattern (compile-matcher-regex ctx (:pattern matcher-data) (inc start-col))]
        (fn [text] (some? (re-find pattern text))))

      :regex-replace
      (let [pattern (compile-matcher-regex ctx (:pattern matcher-data) (+ start-col 3))]
        {:match-fn (fn [text] (re-find pattern text))
         :replace {:pattern pattern
                   :replacement (:replacement matcher-data)}})

      nil)))

(defn- text-matcher-start-col [raw-tree]
  (if-let [tm-node (find-tree-node raw-tree :text-matcher)]
    (let [[start _end] (insta/span tm-node)]
      (inc start))
    1))

(defn- resolve-selector-matchers [ctx selector raw-tree]
  (let [spans (extract-quoted-spans raw-tree)
        quote-col (find-quote-start-col spans)
        tm-start (text-matcher-start-col raw-tree)]
    (case (:type selector)
      (:section :blockquote :paragraph :html :front-matter :task :list-item)
      (let [matcher-data (:matcher selector)
            start-col (if (and matcher-data (= :quoted (:match-type matcher-data)))
                        quote-col
                        tm-start)]
        (assoc selector :matcher (resolve-text-matcher ctx matcher-data start-col)))

      :code
      (let [lang-data (:language-matcher selector)
            matcher-data (:matcher selector)
            lang-fn (when lang-data
                      (if (:raw lang-data)
                        (parse-text-matcher ctx (:raw lang-data) tm-start)
                        (resolve-text-matcher ctx lang-data tm-start)))
            matcher-fn (when matcher-data
                         (let [start-col (if (= :quoted (:match-type matcher-data))
                                           quote-col
                                           tm-start)]
                           (resolve-text-matcher ctx matcher-data start-col)))]
        (assoc selector
               :language-matcher lang-fn
               :matcher matcher-fn))

      (:link :image)
      (let [resolve-link-text (fn [raw-text col]
                                (when raw-text
                                  (let [trimmed (string/trim raw-text)]
                                    (when (seq trimmed)
                                      (parse-text-matcher ctx trimmed col)))))]
        (assoc selector
               :matcher (resolve-link-text (:raw (:matcher selector))
                                           (if (= :link (:type selector)) 2 3))
               :url-matcher (resolve-link-text (:raw (:url-matcher selector))
                                               (some-> (find-tree-node raw-tree :link-url)
                                                       insta/span first (+ 1)))))

      :table
      (let [col-raw (:raw (:col-matcher selector))
            row-raw (:raw (:row-matcher selector))
            has-row-section (:has-row-section selector)
            col-text (when col-raw (string/trim col-raw))
            row-text (when row-raw (string/trim row-raw))]
        (when (and (not has-row-section) (or (nil? col-text) (= "" col-text) (= "*" col-text)))
          (throw-parse-error ctx 1 "expected valid query"))
        (when (and has-row-section (or (nil? col-text) (= "" col-text)))
          (let [table-texts (filter #(and (vector? %) (= :table-text (first %)))
                                    (tree-seq vector? rest raw-tree))
                first-tt (first table-texts)
                [_ end] (insta/span first-tt)
                col (inc end)]
            (throw-parse-error ctx col
                               "table column matcher cannot empty; use an explicit \"*\""
                               :pointer-style :point)))
        (assoc selector
               :col-matcher (parse-text-matcher ctx col-text)
               :row-matcher (parse-text-matcher ctx row-text)))

      selector)))

(defn- parse-selector-insta [ctx s]
  (let [s (string/trim s)
        raw-tree (insta/parse seg-parser s)]
    (if (insta/failure? raw-tree)
      (translate-insta-error ctx s)
      (let [transformed (insta/transform segment-transform raw-tree)]
        (resolve-selector-matchers ctx transformed raw-tree)))))

(defn- parse-pipeline-insta [selector-str]
  (let [parse-ctx {:parse/input selector-str}
        segments (split-pipeline selector-str)]
    (mapv (fn [{:keys [text offset]}]
            (let [segment-ctx (assoc parse-ctx :parse/offset (dec offset))]
              (try
                (assoc (parse-selector-insta segment-ctx text) :offset offset)
                (catch clojure.lang.ExceptionInfo e
                  (let [{:keys [type col message]} (ex-data e)]
                    (if (and (= :parse-error type)
                             (> offset 1)
                             (= col offset)
                             (= message "expected valid query"))
                      (throw-parse-error segment-ctx 1 "expected end of input or selector")
                      (throw e)))))))
          segments)))

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

(defn- current-section-level [{:keys [current]}]
  (some-> current :heading :heading-level))

(defn- close-current-section [{:keys [sections current]}]
  {:sections (cond-> sections current (conj current))
   :current nil})

(defn- start-section [state heading]
  (assoc (close-current-section state)
         :current {:heading heading :body []}))

(defn- append-to-current-section [state node]
  (update-in state [:current :body] conj node))

(defn- finish-sections [state]
  (:sections (close-current-section state)))

(defn- range-section-step [state node lo hi]
  (let [node-heading? (= :heading (:type node))
        node-level (:heading-level node)
        current-level (current-section-level state)
        in-range? (and node-heading?
                       (<= lo node-level hi))
        closes-current? (and current-level
                             node-heading?
                             (<= node-level current-level))]
    (cond
      (and in-range?
           (or (nil? current-level)
               closes-current?))
      (start-section state node)

      (and closes-current?
           (not in-range?))
      (close-current-section state)

      current-level
      (append-to-current-section state node)

      :else
      state)))

(defn- level-section-step [state node level]
  (let [node-heading? (= :heading (:type node))
        current-level (current-section-level state)]
    (cond
      (and node-heading?
           (<= (:heading-level node) level))
      (start-section state node)

      current-level
      (append-to-current-section state node)

      :else
      state)))

(defn- slice-sections [{:keys [level level-range]} nodes]
  (cond
    level-range
    (let [[lo hi] level-range]
      (-> (reduce (fn [state node]
                    (range-section-step state node lo hi))
                  {:sections [] :current nil}
                  nodes)
          finish-sections))

    level
    (-> (reduce (fn [state node]
                  (level-section-step state node level))
                {:sections [] :current nil}
                nodes)
        finish-sections)

    :else
    (top-level-sections nodes)))

(def ^:private result-separator
  "Sentinel node interposed between results by filter functions.
   emit-markdown uses these to place --- separators."
  {:type :result-separator})

(defn- strip-separators
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

(defn- section-filter [{:keys [level level-range matcher] :as selector} nodes]
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

(defn- walk-ast
  "Lazy depth-first pre-order traversal of AST nodes."
  [nodes]
  (tree-seq (fn [n] (and (map? n) (seq (:content n))))
            :content
            {:type :root :content nodes}))

(defn- collect-nodes-deep [types nodes]
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

(defn- list-filter [{:keys [list-kind matcher]} nodes]
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

(defn- task-filter [{:keys [task-kind list-kind matcher]} nodes]
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
                  [:matcher #(string/join (keep :text (:content %)))]]}
   :paragraph {:node-pred #(#{:paragraph :plain} (:type %))
               :preds [[:matcher md/node->text]]}
   :link {:node-pred #(= :link (:type %))
          :preds [[:matcher md/node->text]
                  [:url-matcher #(get-in % [:attrs :href] "")]]}
   :image {:node-pred #(= :image (:type %))
           :preds [[:matcher md/node->text]
                   [:url-matcher #(get-in % [:attrs :src] "")]]}
   :html {:node-pred #(#{:html-block :html-inline} (:type %))
          :preds [[:matcher #(string/join (keep :text (:content %)))]]}})

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

(defn- front-matter-filter [{:keys [format matcher]} nodes]
  (let [fm-nodes (collect-nodes-deep #{:front-matter} nodes)]
    (cond->> fm-nodes
      format (filter #(= format (:format %)))
      matcher (filter #(text-matches? matcher (or (:raw %) ""))))))

(defn- rebuild-table [table col-idxs matched-rows]
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
  (let [trimmed (string/trim line)
        content (cond-> trimmed
                  (string/starts-with? trimmed "|") (subs 1))
        content (cond-> content
                  (string/ends-with? content "|") (subs 0 (dec (count content))))]
    (mapv string/trim (string/split content #"\|"))))

(defn- find-separator-row-index
  "Find the index of the separator row (cells matching :?-+:?)."
  [cells-by-row]
  (first (keep-indexed
          (fn [i cells]
            (when (every? #(re-matches #"\s*:?-+:?\s*" %) cells)
              i))
          cells-by-row)))

(defn- parse-cell-alignment
  "Parse alignment from a separator cell."
  [cell]
  (let [t (string/trim cell)]
    (cond
      (and (string/starts-with? t ":") (string/ends-with? t ":")) "center"
      (string/starts-with? t ":") "left"
      (string/ends-with? t ":") "right"
      :else "none")))

(defn- parse-alignments
  "Parse alignments from separator cells, padding to max-cols with 'none'."
  [sep-cells max-cols]
  (when sep-cells
    (into (mapv parse-cell-alignment sep-cells)
          (repeat (- max-cols (count sep-cells)) "none"))))

(defn- pad-row-to-length
  "Pad a row vector to the desired length with empty strings."
  [row target-length]
  (into (vec row)
        (repeat (- target-length (count row)) "")))

(defn- make-inline-cell
  "Build a table cell node with inline content from text."
  [type text]
  {:type type
   :content (if (string/blank? text)
              []
              (-> (md/parse text) :content first :content (or [])))})

(defn- parse-raw-table-cells [raw]
  (->> (string/split-lines raw)
       (mapv parse-table-row-cells)))

(defn- derive-raw-table-shape [all-cells]
  (let [sep-idx (find-separator-row-index all-cells)
        header-cells (first all-cells)
        body-cell-rows (if sep-idx
                         (subvec all-cells (inc sep-idx))
                         (subvec all-cells 1))
        max-cols (apply max (count header-cells) (map count body-cell-rows))
        sep-cells (when sep-idx (nth all-cells sep-idx))]
    {:header-cells header-cells
     :body-cell-rows body-cell-rows
     :max-cols max-cols
     :sep-cells sep-cells}))

(defn- pad-raw-table-shape
  [{:keys [header-cells body-cell-rows max-cols sep-cells] :as table-shape}]
  (assoc table-shape
         :padded-header-cells (pad-row-to-length header-cells max-cols)
         :padded-body-cell-rows (mapv #(pad-row-to-length % max-cols) body-cell-rows)
         :alignments (parse-alignments sep-cells max-cols)))

(defn- build-table-row [cell-type cells]
  {:type :table-row
   :content (mapv #(make-inline-cell cell-type %) cells)})

(defn- build-normalized-table-content
  [{:keys [padded-header-cells padded-body-cell-rows]}]
  [{:type :table-head
    :content [(build-table-row :table-header padded-header-cells)]}
   {:type :table-body
    :content (mapv #(build-table-row :table-data %) padded-body-cell-rows)}])

(defn- normalize-table-from-raw
  "Given a table node with :raw-table, parses raw cells and rebuilds
   the table AST with all columns (including extra body columns)."
  [table]
  (if-let [raw (:raw-table table)]
    (let [all-cells (parse-raw-table-cells raw)
          table-shape (derive-raw-table-shape all-cells)
          padded-shape (pad-raw-table-shape table-shape)
          table-content (build-normalized-table-content padded-shape)]
      (-> table
          (assoc :content table-content)
          (assoc :alignments (:alignments padded-shape))
          (assoc :normalized? true)
          (dissoc :raw-table)))
    table))

(defn- prepare-table-for-filtering [table]
  (let [table (if (:raw-table table)
                (normalize-table-from-raw table)
                table)
        head-row (-> table :content first :content first)
        headers (mapv md/node->text (:content head-row))
        body-rows (-> table :content second :content)]
    {:table table
     :headers headers
     :body-rows body-rows}))

(defn- select-table-column-indexes [col-matcher headers]
  (if col-matcher
    (keep-indexed (fn [i header]
                    (when (text-matches? col-matcher header)
                      i))
                  headers)
    (range (count headers))))

(defn- row-matches? [row-matcher row]
  (some #(text-matches? row-matcher (md/node->text %))
        (:content row)))

(defn- select-table-rows [row-matcher body-rows]
  (if row-matcher
    (filter #(row-matches? row-matcher %) body-rows)
    body-rows))

(defn- table-filter [{:keys [col-matcher row-matcher]} nodes]
  (let [tables (collect-nodes-deep #{:table} nodes)]
    (for [table tables
          :let [{:keys [table headers body-rows]}
                (prepare-table-for-filtering table)
                col-idxs (select-table-column-indexes col-matcher headers)
                matched-rows (select-table-rows row-matcher body-rows)]
          :when (seq col-idxs)]
      (rebuild-table table col-idxs matched-rows))))

(def ^:private complex-filter-fns
  {:section section-filter
   :list-item list-filter
   :task task-filter
   :front-matter front-matter-filter
   :table table-filter})

(defn- selector->filter-fn [selector]
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

(defn- inline-text-positions [entries]
  (loop [remaining entries
         pos 0
         positions []]
    (if-let [{:keys [text path]} (first remaining)]
      (recur (rest remaining)
             (+ pos (count text))
             (conj positions {:path path
                              :start pos
                              :end (+ pos (count text))
                              :text text}))
      positions)))

(defn- find-affected-cross-boundary-span [pattern positions]
  (let [concat-text (apply str (map :text positions))
        matcher (re-matcher pattern concat-text)]
    (when (.find matcher)
      (let [match-start (.start matcher)
            match-end (.end matcher)
            affected (filterv (fn [{:keys [start end]}]
                                (and (< start match-end) (> end match-start)))
                              positions)]
        {:match-start match-start
         :match-end match-end
         :match-text (.group matcher)
         :affected affected}))))

(defn- redistribute-replacement-text
  [{:keys [affected match-start match-end match-text]} pattern replacement]
  (let [replacement-text (string/replace match-text pattern replacement)]
    (mapv (fn [{:keys [path start text] :as position}]
            (let [first-affected? (= position (first affected))
                  last-affected? (= position (peek affected))]
              {:path path
               :text (cond
                       first-affected?
                       (str (subs text 0 (- match-start start)) replacement-text)

                       last-affected?
                       (subs text (- match-end start))

                       :else
                       "")}))
          affected)))

(defn- apply-redistributed-inline-text [content redistributed-text]
  (reduce (fn [tree {:keys [path text]}]
            (assoc-in tree (conj path :text) text))
          (vec content)
          redistributed-text))

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
      (let [positions (-> node :content flatten-inline-texts inline-text-positions)]
        (if-let [{:keys [affected] :as span}
                 (and (seq positions)
                      (find-affected-cross-boundary-span pattern positions))]
          (if (<= (count affected) 1)
            node
            (let [redistributed-text (redistribute-replacement-text span pattern replacement)
                  new-content (apply-redistributed-inline-text (:content node)
                                                               redistributed-text)]
              (apply-replacement-cross-boundary (assoc node :content new-content)
                                                pattern
                                                replacement)))
          node))
      node)))

(defn- selector-replacements [selectors matcher-key]
  (into [] (keep #(-> % matcher-key :replace)) selectors))

(defn- make-replacement-xf [replacements]
  (apply comp
         (reverse
          (map (fn [{:keys [pattern replacement]}]
                 #(string/replace % pattern replacement))
               replacements))))

(defn- apply-postwalk-replacements
  [results replacements node-pred update-fn]
  (if (seq replacements)
    (let [value-xf (make-replacement-xf replacements)]
      (walk/postwalk
       (fn [node]
         (if (and (map? node) (node-pred node))
           (update-fn node value-xf)
           node))
       results))
    results))

(defn- apply-text-node-replacements [results text-replacements]
  (apply-postwalk-replacements
   results
   text-replacements
   #(= :text (:type %))
   (fn [node text-xf]
     (update node :text text-xf))))

(defn- apply-cross-boundary-text-replacements [results text-replacements]
  (if (seq text-replacements)
    (mapv (fn [node]
            (reduce (fn [current-node {:keys [pattern replacement]}]
                      (apply-replacement-cross-boundary current-node pattern replacement))
                    node
                    text-replacements))
          results)
    results))

(defn- apply-url-replacements [results url-replacements]
  (apply-postwalk-replacements
   results
   url-replacements
   #(contains? #{:link :image} (:type %))
   (fn [node url-xf]
     (case (:type node)
       :link (update-in node [:attrs :href] url-xf)
       :image (update-in node [:attrs :src] url-xf)
       node))))

(defn- apply-language-replacements [results language-replacements]
  (apply-postwalk-replacements
   results
   language-replacements
   #(and (= :code (:type %)) (:language %))
   (fn [node language-xf]
     (update node :language language-xf))))

(defn- apply-replacements [results selectors]
  (let [text-replacements (selector-replacements selectors :matcher)
        url-replacements (selector-replacements selectors :url-matcher)
        language-replacements (selector-replacements selectors :language-matcher)]
    (-> results
        (apply-text-node-replacements text-replacements)
        (apply-cross-boundary-text-replacements text-replacements)
        (apply-url-replacements url-replacements)
        (apply-language-replacements language-replacements))))

(defn- run-pipeline [nodes selector-str]
  (let [selectors (parse-pipeline-insta selector-str)
        result (reduce (fn [nodes sel]
                         ((selector->filter-fn sel) nodes))
                       nodes
                       selectors)]
    (apply-replacements result selectors)))

(declare emit-inline-str)

(defn- ref-key-comparator
  "Comparator for reference keys that handles mixed numeric/string types.
   Numbers sort before strings, strings sort case-insensitively."
  [a b]
  (let [a-num (if (number? a) a (parse-long (str a)))
        b-num (if (number? b) b (parse-long (str b)))]
    (cond
      (and a-num b-num) (compare a-num b-num)
      a-num -1
      b-num 1
      :else (compare (string/lower-case (str a)) (string/lower-case (str b))))))

(defn- detect-link-forms
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
  [ctx node text href link-form]
  (let [{:emit/keys [url->ref counter refs]} ctx
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
  [ctx node text href link-form]
  (let [{:emit/keys [refs]} ctx
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

(defn- emit-footnote-ref [ctx label]
  (if-let [label->num (:emit/footnote-label->num ctx)]
    (if (not= false (:emit/renumber-footnotes ctx))
      (let [num (or (get @label->num label)
                    (let [n (swap! (:emit/footnote-counter ctx) inc)]
                      (swap! label->num assoc label n)
                      n))]
        (str "[^" num "]"))
      (do (swap! label->num assoc label label)
          (str "[^" label "]")))
    (str "[^" label "]")))

(defn- emit-inline-link [ctx node]
  (let [text (emit-inline-str ctx (:content node))
        href (get-in node [:attrs :href])
        link-format (:emit/link-format ctx)
        link-forms (:emit/link-forms ctx)
        link-form (when link-forms (get link-forms [text href]))]
    (case link-format
      "never-inline" (emit-link-never-inline ctx node text href link-form)
      "keep" (emit-link-keep ctx node text href link-form)
      "inline" (emit-link-inline node text href link-form)
      "reference" (emit-link-never-inline ctx node text href link-form)
      (str "[" text "](" href ")"))))

(defn- emit-inline-image [ctx node]
  (if (and ctx (contains? #{"reference" "never-inline"} (:emit/link-format ctx)))
    (let [{:emit/keys [url->ref counter refs]} ctx
          src (get-in node [:attrs :src])
          alt (emit-inline-str ctx (:content node))
          dedup-key [src nil]
          ref-num (or (get @url->ref dedup-key)
                      (let [n (swap! counter inc)]
                        (swap! url->ref assoc dedup-key n)
                        (swap! refs assoc n {:url src})
                        n))]
      (str "![" alt "][" ref-num "]"))
    (str "![" (emit-inline-str ctx (:content node)) "]("
         (get-in node [:attrs :src]) ")")))

(defn- emit-inline
  ([node]
   (emit-inline nil node))
  ([ctx node]
   (case (:type node)
     :text (:text node)
     :softbreak "\n"
     :hardbreak "\n\n"
     :strong (str "**" (emit-inline-str ctx (:content node)) "**")
     :em (str "_" (emit-inline-str ctx (:content node)) "_")
     :strikethrough (str "~~" (emit-inline-str ctx (:content node)) "~~")
     :footnote-ref (emit-footnote-ref ctx (:label node))
     :link (emit-inline-link ctx node)
     :image (emit-inline-image ctx node)
     :monospace (str "`" (emit-inline-str ctx (:content node)) "`")
     :formula (str "$" (emit-inline-str ctx (:content node)) "$")
     (if-let [content (:content node)]
       (emit-inline-str ctx content)
       (or (:text node) "")))))

(defn- extract-table-alignments [markdown-text]
  (let [lines (string/split-lines markdown-text)]
    (->> lines
         (filter #(re-matches #"\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)+\|?\s*" %))
         (mapv (fn [line]
                 (->> (string/split (string/trim line) #"\|")
                      (remove string/blank?)
                      (mapv (fn [cell]
                              (let [cell (string/trim cell)]
                                (cond
                                  (and (string/starts-with? cell ":") (string/ends-with? cell ":")) "center"
                                  (string/starts-with? cell ":") "left"
                                  (string/ends-with? cell ":") "right"
                                  :else "none"))))))))))

(defn- attach-table-alignments [nodes alignments-seq]
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
  (string/join (keep :text (:content node))))

(defn- extract-raw-tables [markdown-text]
  (let [lines (string/split-lines markdown-text)]
    (->> (map-indexed vector lines)
         (partition-by (fn [[_ line]] (string/starts-with? (string/trim line) "|")))
         (filter (fn [group] (string/starts-with? (string/trim (second (first group))) "|")))
         (mapv (fn [group] (string/join "\n" (map second group)))))))

(defn- attach-raw-tables [nodes raw-tables]
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

(defn- emit-node
  ([node]
   (emit-node nil node))
  ([ctx node]
   (case (:type node)
     :heading (str (apply str (repeat (:heading-level node) "#")) " "
                   (emit-inline-str ctx (:content node)))
     :paragraph (emit-inline-str ctx (:content node))
     :plain (emit-inline-str ctx (:content node))
     :bullet-list (string/join "\n" (map (partial emit-node ctx) (:content node)))
     :todo-list (string/join "\n" (map (partial emit-node ctx) (:content node)))
     :numbered-list (string/join "\n"
                                 (map-indexed
                                  (fn [i item]
                                    (let [n (+ i (or (get-in node [:attrs :start]) 1))
                                          item-with-order (assoc-in item [:attrs :order] n)]
                                      (emit-node ctx item-with-order)))
                                  (:content node)))
     :list-item (let [order (get-in node [:attrs :order])
                      prefix (if order (str order ". ") "- ")
                      indent (apply str (repeat (count prefix) " "))
                      parts (map (partial emit-node ctx) (:content node))
                      combined (string/join "\n\n" parts)
                      lines (string/split combined #"\n" -1)]
                  (str prefix (first lines)
                       (when (> (count lines) 1)
                         (str "\n"
                              (string/join "\n"
                                           (map #(if (string/blank? %) "" (str indent %))
                                                (rest lines)))))))
     :todo-item (let [order (get-in node [:attrs :order])
                      prefix (if order (str order ". ") "- ")]
                  (str prefix "[" (if (get-in node [:attrs :checked]) "x" " ") "] "
                       (string/join (str "\n" (apply str (repeat (+ (count prefix) 4) " ")))
                                    (map (partial emit-node ctx) (:content node)))))
     :blockquote (string/join "\n" (map #(str "> " (emit-node ctx %)) (:content node)))
     :code (str "```" (or (:language node) "") "\n"
                (string/trimr (content-text node))
                "\n```")
     :ruler "---"
     :html-block (content-text node)
     :link (emit-inline ctx (assoc node :type :link))
     :image (emit-inline ctx (assoc node :type :image))
     :table (if (and (:raw-table node) (not (:normalized? node)))
              (:raw-table node)
              (let [[head body] (:content node)
                    head-row (first (:content head))
                    body-rows (:content body)
                    all-rows (cons head-row body-rows)
                    row-texts (mapv (fn [row]
                                      (mapv (fn [cell]
                                              (emit-inline-str ctx (:content cell)))
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
                                 (str "| " (string/join " | " (map pad-right texts col-widths)) " |"))
                    sep-row (str "|" (string/join "|" (map make-sep aligns col-widths)) "|")]
                (string/join "\n"
                             (concat [(format-row (first row-texts)) sep-row]
                                     (map format-row (rest row-texts))))))
     :block-formula (str "$$\n" (emit-inline-str ctx (:content node)) "\n$$")
     :front-matter (let [delim (if (= :toml (:format node)) "+++" "---")]
                     (str delim "\n" (:raw node) "\n" delim))
     ;; fallback
     (if-let [content (:content node)]
       (string/join "\n" (map (partial emit-node ctx) content))
       (or (:text node) "")))))

(defn- kebab->snake [k]
  (string/replace (name k) "-" "_"))

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

(defn- format-ref-definitions [refs-map]
  (when (seq refs-map)
    (string/join "\n"
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
      :else (compare (string/lower-case a) (string/lower-case b)))))

(defn- format-footnote-definitions
  ([footnote-label->num-atom footnotes-by-label renumber?]
   (format-footnote-definitions nil footnote-label->num-atom footnotes-by-label renumber?))
  ([ctx footnote-label->num-atom footnotes-by-label renumber?]
   (when (seq @footnote-label->num-atom)
     (let [emit-fn-body (fn [fn-def]
                          (string/join "\n\n"
                                       (map (fn [block]
                                              (emit-inline-str ctx (:content block)))
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
           (string/join "\n"
                        (map (fn [[_label num body]]
                               (str "[^" num "]: " body))
                             sorted)))
         (let [sorted (sort-by first footnote-label-comparator entries)]
           (string/join "\n"
                        (map (fn [[label _num body]]
                               (str "[^" label "]: " body))
                             sorted))))))))

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

(defn- make-emit-context [raw-md opts footnotes]
  (let [ref-defs (when raw-md (extract-ref-links raw-md))
        link-forms (when raw-md (detect-link-forms raw-md ref-defs))
        link-format (or (:link-format opts) "never-inline")
        link-placement (or (:link-placement opts) "section")
        renumber-footnotes (get opts :renumber-footnotes true)
        footnotes-by-label (when footnotes
                             (into {} (map (juxt :label identity)) footnotes))
        footnote-label->num (atom {})
        footnote-counter (atom 0)
        needs-refs? (contains? #{"reference" "never-inline" "keep"} link-format)]
    {:emit/ref-defs ref-defs
     :emit/link-forms link-forms
     :emit/link-format link-format
     :emit/link-placement link-placement
     :emit/renumber-footnotes renumber-footnotes
     :emit/footnotes-by-label footnotes-by-label
     :emit/footnote-label->num footnote-label->num
     :emit/footnote-counter footnote-counter
     :emit/needs-refs? needs-refs?}))

(defn- make-ref-emission-ctx
  [{:emit/keys [link-format link-forms footnote-label->num
                footnote-counter renumber-footnotes]}
   refs
   counter
   url->ref]
  {:emit/link-format link-format
   :emit/link-forms link-forms
   :emit/refs refs
   :emit/counter counter
   :emit/url->ref url->ref
   :emit/footnote-label->num footnote-label->num
   :emit/footnote-counter footnote-counter
   :emit/renumber-footnotes renumber-footnotes})

(defn- emit-markdown-group-body
  [ctx group]
  (string/join "\n\n" (map (partial emit-node ctx) group)))

(defn- emit-markdown-section-group
  [section-group context counter url->ref]
  (let [refs (atom (sorted-map-by ref-key-comparator))
        ctx (make-ref-emission-ctx context refs counter url->ref)
        body (emit-markdown-group-body ctx section-group)
        defs (format-ref-definitions @refs)]
    (if (seq defs)
      (str body "\n\n" defs)
      body)))

(defn- emit-markdown-section-placement
  [groups group-sep {:emit/keys [footnote-label->num renumber-footnotes
                                 footnotes-by-label]
                     :as context}]
  (let [counter (atom 0)
        url->ref (atom {})
        refs-for-fns (atom (sorted-map-by ref-key-comparator))
        main-output (string/join group-sep
                                 (mapv (fn [group]
                                         (->> (group-nodes-by-section (vec group))
                                              (mapv #(emit-markdown-section-group
                                                      %
                                                      context
                                                      counter
                                                      url->ref))
                                              (string/join "\n\n")))
                                       groups))
        fn-ctx (make-ref-emission-ctx context refs-for-fns counter url->ref)
        fn-defs (when footnotes-by-label
                  (format-footnote-definitions fn-ctx footnote-label->num footnotes-by-label renumber-footnotes))
        ref-defs-from-fns (format-ref-definitions @refs-for-fns)
        suffix (string/join "\n" (remove nil? [ref-defs-from-fns fn-defs]))]
    (if (seq suffix)
      (str main-output "\n\n" suffix)
      main-output)))

(defn- emit-markdown-doc-placement
  [groups group-sep {:emit/keys [footnote-label->num renumber-footnotes
                                 footnotes-by-label]
                     :as context}]
  (let [counter (atom 0)
        url->ref (atom {})
        refs (atom (sorted-map-by ref-key-comparator))
        ctx (make-ref-emission-ctx context refs counter url->ref)
        body (string/join group-sep
                          (mapv (partial emit-markdown-group-body ctx) groups))
        fn-defs (when footnotes-by-label
                  (format-footnote-definitions ctx footnote-label->num footnotes-by-label renumber-footnotes))
        ref-defs (format-ref-definitions @refs)
        defs-sep (if (> (count groups) 1) group-sep "\n\n")
        suffix (string/join "\n" (remove nil? [ref-defs fn-defs]))]
    (if (seq suffix)
      (str body defs-sep suffix)
      body)))

(defn- emit-markdown-inline-format
  [groups group-sep {:emit/keys [link-format link-forms footnote-label->num
                                 footnote-counter renumber-footnotes
                                 footnotes-by-label]}]
  (let [ctx {:emit/link-format link-format
             :emit/link-forms link-forms
             :emit/footnote-label->num footnote-label->num
             :emit/footnote-counter footnote-counter
             :emit/renumber-footnotes renumber-footnotes}
        main-output (string/join group-sep
                                 (mapv (fn [group]
                                         (string/join "\n\n" (map (partial emit-node ctx) group)))
                                       groups))
        fn-defs (when footnotes-by-label
                  (format-footnote-definitions ctx footnote-label->num footnotes-by-label renumber-footnotes))]
    (if (seq fn-defs)
      (str main-output "\n\n" fn-defs)
      main-output)))

(defn- emit-markdown
  ([nodes] (emit-markdown nodes nil))
  ([nodes opts]
   (let [raw-md (:raw-md opts)
         footnotes (when-let [fns (:footnotes (:ast opts))]
                     (when (seq fns) fns))
         context (make-emit-context raw-md opts footnotes)
         groups (separate-results nodes)
         group-sep (if (:no-br opts) "\n\n" "\n\n   -----\n\n")]
     (if (:emit/needs-refs? context)
       (if (= "section" (:emit/link-placement context))
         (emit-markdown-section-placement groups group-sep context)
         (emit-markdown-doc-placement groups group-sep context))
       (emit-markdown-inline-format groups group-sep context)))))

(defn- emit-inline-str
  ([content]
   (emit-inline-str nil content))
  ([ctx content]
   (apply str (map #(emit-inline ctx %) content))))

(declare nodes->items)

(defn- assoc-when-some [m k value]
  (cond-> m
    (some? value) (assoc k value)))

(defn- assoc-when-seq [m k value]
  (cond-> m
    (seq value) (assoc k value)))

(defn- code-block-item [node]
  (let [info (:info node)
        [lang metadata] (when (seq info)
                          (string/split info #"\s+" 2))]
    (-> {:code (content-text node)
         :type "code"}
        (assoc-when-seq :language lang)
        (assoc-when-seq :metadata metadata))))

(defn- linked-item [ctx label-key content url title]
  (-> {label-key (emit-inline-str ctx content)
       :url url}
      (assoc-when-some :title title)))

(defn- node->item
  ([node]
   (node->item nil node))
  ([ctx node]
   (case (:type node)
     :heading
     {:section {:depth (:heading-level node)
                :title (emit-inline-str ctx (:content node))}}

     (:paragraph :plain)
     {:paragraph (emit-inline-str ctx (:content node))}

     :code
     {:code-block (code-block-item node)}

     :block-formula
     {:code-block {:code (:text node)
                   :type "math"}}

     :link
     {:link (linked-item ctx
                         :display
                         (:content node)
                         (get-in node [:attrs :href])
                         (get-in node [:attrs :title]))}

     :image
     {:image (linked-item ctx
                          :alt
                          (:content node)
                          (get-in node [:attrs :src])
                          (get-in node [:attrs :title]))}

     :blockquote
     {:block-quote (nodes->items ctx (:content node))}

     :bullet-list
     {:list (mapv (fn [li] {:item (nodes->items ctx (:content li))})
                  (:content node))}

     :numbered-list
     {:list (vec (map-indexed
                  (fn [i li]
                    {:item (nodes->items ctx (:content li))
                     :index (+ i (or (get-in node [:attrs :start]) 1))})
                  (:content node)))}

     :todo-list
     {:list (mapv (fn [li]
                    (cond-> {:item (nodes->items ctx (:content li))}
                      (= :todo-item (:type li))
                      (assoc :checked (boolean (get-in li [:attrs :checked])))))
                  (:content node))}

     :table
     (let [[head body] (:content node)
           emit-row (fn [row]
                      (mapv #(emit-inline-str ctx (:content %)) (:content row)))
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

     (if-let [content (:content node)]
       {:paragraph (emit-inline-str ctx content)}
       {}))))

(defn- finalize-section-item [section-item]
  (update-in section-item [:section :body] vec))

(defn- same-level-or-new-section? [current-section item]
  (or (nil? current-section)
      (<= (get-in item [:section :depth])
          (get-in current-section [:section :depth]))))

(defn- start-next-section [remaining result current-section item]
  [(next remaining)
   (cond-> result
     current-section (conj (finalize-section-item current-section)))
   (assoc-in item [:section :body] [])])

(defn- collect-deeper-section-nodes [remaining current-depth node]
  (loop [r (next remaining)
         collected [node]]
    (if-not (seq r)
      [collected nil]
      (let [next-node (first r)]
        (if (and (= :heading (:type next-node))
                 (<= (:heading-level next-node) current-depth))
          [collected (seq r)]
          (recur (next r) (conj collected next-node)))))))

(defn- append-deeper-section [ctx remaining result current-section node]
  (let [current-depth (get-in current-section [:section :depth])
        [sub-nodes rest-nodes] (collect-deeper-section-nodes remaining current-depth node)]
    [rest-nodes
     result
     (update-in current-section [:section :body]
                into
                (nodes->items ctx sub-nodes))]))

(defn- accumulate-body-item [remaining result current-section item]
  (if current-section
    [(next remaining)
     result
     (update-in current-section [:section :body] conj item)]
    [(next remaining)
     (conj result item)
     nil]))

(defn- nodes->items
  ([nodes]
   (nodes->items nil nodes))
  ([ctx nodes]
   (loop [remaining (seq nodes)
          result []
          current-section nil]
     (if-not remaining
       (cond-> result
         current-section (conj (finalize-section-item current-section)))
       (let [node (first remaining)
             item (node->item ctx node)
             [next-remaining next-result next-current-section]
             (cond
               (and (:section item)
                    (same-level-or-new-section? current-section item))
               (start-next-section remaining result current-section item)

               (:section item)
               (append-deeper-section ctx remaining result current-section node)

               :else
               (accumulate-body-item remaining result current-section item))]
         (recur next-remaining next-result next-current-section))))))

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
    [(string/trimr (md/node->text node))]

    :ruler []

    :table
    (let [rows (mapcat :content (:content node))]
      (map (fn [row]
             (string/trim (string/join " " (map #(string/trim (md/node->text %)) (:content row)))))
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

(defn- build-json-footnotes
  ([footnote-label->num-atom footnotes-by-label]
   (build-json-footnotes nil footnote-label->num-atom footnotes-by-label))
  ([ctx footnote-label->num-atom footnotes-by-label]
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
                                                                 {:paragraph (emit-inline-str ctx (:content block))})
                                                               (:content fn-def))]
                                          [(str num) body-parts])))
                                    new-labels))]
                         (recur (into emitted-labels new-labels)
                                (into entries new-entries))))))]
     (when (seq entries)
       (into (sorted-map) entries)))))

(defn- format-plain-output [nodes use-double-newline?]
  (let [texts (mapcat node->plain-texts nodes)
        sep (if use-double-newline? "\n\n" "\n")]
    (string/join sep texts)))

(defn- make-structured-output-context [raw-md footnotes]
  (let [ref-links (when raw-md (extract-ref-links raw-md))
        link-forms (when raw-md (detect-link-forms raw-md ref-links))
        footnotes-by-label (when footnotes
                             (into {} (map (juxt :label identity)) footnotes))]
    {:emit/counter (atom 0)
     :emit/url->ref (atom (if ref-links
                            (reduce-kv (fn [m ref {:keys [url]}]
                                         (assoc m url ref))
                                       {} ref-links)
                            {}))
     :emit/refs (atom (sorted-map-by ref-key-comparator))
     :emit/footnote-label->num (atom {})
     :emit/footnote-counter (atom 0)
     :emit/footnotes-by-label footnotes-by-label
     :emit/link-forms link-forms
     :ref-links ref-links}))

(defn- make-json-structured-output-ctx
  [{:emit/keys [counter url->ref refs footnote-label->num footnote-counter
                footnotes-by-label link-forms]
    :as structured-output-context}]
  (assoc structured-output-context
         :emit/link-format "never-inline"
         :emit/link-forms link-forms
         :emit/counter counter
         :emit/url->ref url->ref
         :emit/refs refs
         :emit/footnote-label->num footnote-label->num
         :emit/footnote-counter footnote-counter
         :emit/footnotes-by-label footnotes-by-label
         :emit/renumber-footnotes true))

(defn- structured-json-items [nodes clean-nodes has-selector? json-ctx]
  (if has-selector?
    (let [groups (split-by-separator nodes)]
      (vec (mapcat #(nodes->items json-ctx (wrap-list-items %)) groups)))
    (nodes->items json-ctx (wrap-list-items clean-nodes))))

(defn- trim-json-code-blocks [items]
  (walk/postwalk
   (fn [x]
     (if (and (map? x) (:code-block x))
       (update-in x [:code-block :code] string/trimr)
       x))
   items))

(defn- structured-json-links [refs]
  (when (seq @refs)
    (into (sorted-map-by ref-key-comparator)
          (map (fn [[ref-key {:keys [url title]}]]
                 [(str ref-key)
                  (cond-> {:url url}
                    title (assoc :title title))]))
          @refs)))

(defn- format-json-structured-output [nodes clean-nodes has-selector? structured-output-context]
  (let [json-ctx (make-json-structured-output-ctx structured-output-context)
        {:emit/keys [refs footnote-label->num footnotes-by-label]
         :keys [ref-links]}
        structured-output-context
        items (-> (structured-json-items nodes clean-nodes has-selector? json-ctx)
                  trim-json-code-blocks)
        json-footnotes (when (and footnotes-by-label (seq @footnote-label->num))
                         (build-json-footnotes json-ctx footnote-label->num footnotes-by-label))
        json-links (structured-json-links refs)
        json-data (items->json-data items)
        json-items (if has-selector?
                     json-data
                     [{:document json-data}])]
    (json/generate-string
     (cond-> {:items json-items}
       json-footnotes (assoc :footnotes json-footnotes)
       json-links (assoc :links json-links)
       (and (seq ref-links) (not has-selector?) (not json-links))
       (assoc :links ref-links))
     {:pretty true})))

(defn- format-edn-structured-output [clean-nodes footnotes]
  (let [items (nodes->items (wrap-list-items clean-nodes))
        result (cond-> {:items items}
                 footnotes (assoc :footnotes footnotes))]
    (with-out-str (pp/pprint result))))

(defn- structured-output-footnotes [opts]
  (when-let [fns (:footnotes (:ast opts))]
    (when (seq fns) fns)))

(defn- clean-structured-output-nodes [nodes]
  (vec (remove #(= :result-separator (:type %)) nodes)))

(defn- format-json-structured-output-for-opts [nodes opts]
  (let [clean-nodes (clean-structured-output-nodes nodes)
        has-selector? (some? (:selector opts))
        footnotes (structured-output-footnotes opts)]
    (format-json-structured-output
     nodes
     clean-nodes
     has-selector?
     (make-structured-output-context (:raw-md opts) footnotes))))

(defn- format-edn-structured-output-for-opts [nodes opts]
  (let [clean-nodes (clean-structured-output-nodes nodes)
        footnotes (structured-output-footnotes opts)]
    (format-edn-structured-output clean-nodes footnotes)))

(defn- format-structured-output [nodes opts output-kw]
  (case output-kw
    :json (format-json-structured-output-for-opts nodes opts)
    :edn (format-edn-structured-output-for-opts nodes opts)))

(defn- format-output [nodes opts]
  (let [output-kw (keyword (or (:output opts) "markdown"))
        output-kw (if (= :md output-kw) :markdown output-kw)]
    (case output-kw
      :markdown (emit-markdown nodes opts)
      :plain (format-plain-output nodes (:br opts))
      (format-structured-output nodes opts output-kw))))

(defn- pre-process-front-matter [input]
  (let [lines (string/split-lines input)
        lines-vec (vec lines)
        extract-front-matter (fn [format delimiter]
                               (let [end-idx (->> (rest lines)
                                                  (keep-indexed (fn [i line]
                                                                  (when (= delimiter line)
                                                                    (inc i))))
                                                  first)]
                                 (if end-idx
                                   {:front-matter {:format format
                                                   :raw (string/join "\n" (subvec lines-vec 1 end-idx))}
                                    :body (string/join "\n" (subvec lines-vec (inc end-idx)))}
                                   {:front-matter nil :body input})))]
    (cond
      (= "---" (first lines))
      (extract-front-matter :yaml "---")

      (= "+++" (first lines))
      (extract-front-matter :toml "+++")

      :else
      {:front-matter nil :body input})))

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

    (string/starts-with? line "> ")
    {:prefix "> " :continuation "> " :content (subs line 2)}

    (re-matches #"^( +)- .*" line)
    (let [[_ indent] (re-matches #"^( +)- .*" line)
          prefix-len (+ (count indent) 2)
          prefix (subs line 0 prefix-len)]
      {:prefix prefix
       :continuation (apply str (repeat prefix-len \space))
       :content (subs line prefix-len)})

    (string/starts-with? line "- ")
    {:prefix "- " :continuation "  " :content (subs line 2)}

    :else
    {:prefix "" :continuation "" :content line}))

(defn- wrap-line
  "Wrap a single line respecting its prefix and the given width."
  [line width]
  (if (string/blank? line)
    line
    (let [{:keys [prefix continuation content no-wrap]} (detect-line-prefix line)]
      (if (or no-wrap (<= (count line) width))
        line
        (let [content-width (- width (count prefix))
              tokens (tokenize-for-wrap content)
              wrapped (wrap-tokens tokens content-width)]
          (string/join "\n"
                    (map-indexed (fn [i l]
                                   (str (if (zero? i) prefix continuation) l))
                                 wrapped)))))))

(defn- wrap-text
  "Wrap all lines in markdown text to the given width."
  [text width]
  (string/join "\n" (map #(wrap-line % width) (string/split text #"\n" -1))))

(def ^:export cli-spec
  "CLI spec: single source of truth for arg parsing, help, and shell completions."
  {:spec
   {:output {:coerce :string :alias :o :ref "<format>"
             :desc "Output format: markdown (default), json, edn, plain"}
    :quiet {:coerce :boolean :alias :q
            :desc "Exit 0 if found, non-0 otherwise (no output)"}
    :br {:coerce :boolean
         :desc "Use blank lines between plain-text blocks"}
    :no-br {:coerce :boolean
            :desc "Omit markdown result separators"}
    :link-format {:coerce :string :ref "<format>"
                  :desc "Link format: never-inline (default), inline, keep, reference"}
    :link-placement {:coerce :string :ref "<placement>"
                     :desc "Link placement: section (default), doc"}
    :link-pos {:coerce :string :ref "<placement>"
               :desc "Alias for --link-placement"}
    :renumber-footnotes {:coerce :boolean
                         :desc "Renumber footnotes (default true; false preserves labels)"}
    :wrap-width {:coerce :long :ref "<cols>"
                 :desc "Wrap output to the given column width"}
    :help {:coerce :boolean :alias :h
           :desc "Show this help"}}
   :args->opts (cons :selector (repeat :files))
   :coerce {:selector :string :files []}
   :no-keyword-opts true
   :order [:output :quiet :br :no-br :link-format :link-placement
           :link-pos :renumber-footnotes :wrap-width :help]})

(defn- format-cli-error
  "Formats a babashka.cli error into a user-friendly message."
  [{:keys [cause option msg value]} args]
  (let [has-dash-selector? (some #(and (string/starts-with? % "- ")
                                       (not (string/starts-with? % "-- ")))
                                 args)]
    (case cause
      :coerce (if has-dash-selector?
                (str "Error: selector starting with '-' needs a '--' separator.\n"
                     "  Example: bbg mdq -- '- selector'")
                (if (string/includes? msg "(implicit) true")
                  (str "Error: option --" (name option) " requires a value")
                  (str "Error: invalid value for --" (name option)
                       " (expected " (name (get-in (:spec cli-spec) [option :coerce]))
                       ", got " (pr-str value) ")")))
      :restrict (str "Error: unknown option --" (name option))
      (str "Error: " msg))))

(defn- parse-args [args]
  (try
    (let [{:keys [opts args]} (cli/parse-args args cli-spec)
          opts (if-let [v (:link-pos opts)]
                 (-> opts (dissoc :link-pos) (assoc :link-placement v))
                 opts)
          opts (if (seq args)
                 (if (:selector opts)
                   (update opts :files (fnil into []) args)
                   (cond-> (assoc opts :selector (first args))
                     (next args) (assoc :files (vec (rest args)))))
                 opts)]
      opts)
    (catch Exception e
      (let [data (ex-data e)]
        (if (= :org.babashka/cli (:type data))
          {:error (format-cli-error data args) :arg-error true}
          (throw e))))))

(defn- help-text []
  (str "Usage: bbg mdq [options] '<selector>' [file ...]\n\n"
       "Options:\n"
       (cli/format-opts cli-spec)))

(defn- process
  "Processes markdown input with given args. Returns a map with:
   :output  - the formatted output string (or nil)
   :exit    - suggested exit code (0 for success, 1 for no results)
   :error   - error string if processing failed"
  [input args]
  (let [opts (parse-args args)]
    (if-let [err (:error opts)]
      {:error err :exit 1 :arg-error (:arg-error opts)}
      (if (:help opts)
        {:output (help-text)
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
                 :exit 1}))))))))

(defn process-inputs
  "Process inputs based on args. Returns {:output :exit :error}.
   io-fns: {:read-stdin (fn [] string), :resolve-file (fn [path] string)}"
  [args {:keys [read-stdin resolve-file]}]
  (let [opts (parse-args args)]
    (if-let [err (:error opts)]
      {:error err :exit 1 :arg-error (:arg-error opts)}
      (let [files (:files opts)]
        (cond
          (:help opts)
          (process "" args)

          (seq files)
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
            {:output (when (seq outputs) (string/join "\n" (map string/trimr outputs)))
             :error (when (seq errors) (string/join "\n" errors))
             :exit (if any-fail? 1 0)})

          :else
          (process (read-stdin) args))))))

(defn ^:export exec! [args]
  (let [{:keys [output error exit arg-error]}
        (process-inputs args
                        {:read-stdin #(slurp *in*)
                         :resolve-file (fn [path]
                                         (slurp (java.io.File. path)))})
        error (if arg-error (str error "\n\n" (help-text)) error)]
    (when output (println output))
    (when error (binding [*out* *err*] (println error)))
    (System/exit exit)))
