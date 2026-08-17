(ns assoc-exts
  "Promote an app as the default handler for coding file extensions.
   Demotes other editor-like claimants via lsregister. No consent dialogs."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as string]))

(def coding-extensions
  ["ascx" "asp" "aspx" "bash" "bash_login" "bash_logout" "bash_profile"
   "bashrc" "bat" "bowerrc" "c" "c++" "cc" "cfg" "cjs" "clj" "cljs" "cljx"
   "clojure" "cls" "cmake" "cmd" "code-workspace" "coffee" "config"
   "containerfile" "cpp" "cs" "cshtml" "csproj" "css" "csv" "csx" "ctp"
   "cxx" "dart" "diff" "dockerfile" "dot" "dtd" "editorconfig" "edn" "erb"
   "eyaml" "eyml" "fs" "fsi" "fsscript" "fsx" "gemspec" "gitattributes"
   "gitconfig" "gitignore" "go" "gradle" "groovy" "h" "h++" "handlebars"
   "hbs" "hh" "hpp" "htm" "html" "hxx" "ini" "ipynb" "jade" "jav" "java"
   "js" "jscsrc" "jshintrc" "jshtm" "json" "jsp" "jsx" "less" "lock" "log"
   "lua" "m" "makefile" "markdown" "md" "mdoc" "mdown" "mdtext" "mdtxt"
   "mdwn" "mjs" "mk" "mkd" "mkdn" "ml" "mli" "mm" "php" "phtml" "pl" "pl6"
   "plist" "pm" "pm6" "pod" "pp" "profile" "properties" "ps1" "psd1" "psgi"
   "psm1" "pug" "py" "pyi" "r" "rb" "rhistory" "rprofile" "rs" "rst" "rt"
   "sass" "scss" "sh" "shtml" "sql" "svg" "swift" "t" "tex" "toml" "ts"
   "tsx" "txt" "vb" "vue" "wxi" "wxl" "wxs" "xaml" "xcodeproj" "xcworkspace"
   "xhtml" "xml" "yaml" "yml" "zlogin" "zlogout" "zprofile" "zsh" "zshenv"
   "zshrc"])

(def coding-extension-set (set coding-extensions))

(def cli-spec
  {:coerce {:force :boolean
            :dry-run :boolean
            :list :boolean}
   :alias {:f :force
           :n :dry-run
           :l :list}
   :spec {:force {:alias :f
                  :desc "Keep unregistering whoever still owns a selected extension"}
          :dry-run {:alias :n
                    :desc "Print the promote/demote plan without changing Launch Services"}
          :list {:alias :l
                 :desc "Print current default app for each selected extension"}}})

(def ^:private lsregister-bin
  "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister")

(def ^:private defaults-swift
  (string/join "\n"
               ["import AppKit"
                "let tmp = FileManager.default.temporaryDirectory"
                "for ext in CommandLine.arguments.dropFirst() {"
                "  let url = tmp.appendingPathComponent(\"probe.\\(ext)\")"
                "  try? Data().write(to: url)"
                "  let path = NSWorkspace.shared.urlForApplication(toOpen: url)?.path ?? \"\""
                "  print(ext, path, separator: \"\\t\")"
                "}"]))

(defn parse-extensions
  "Parse a comma-separated extension list. Drops leading dots and blanks."
  [s]
  (->> (string/split (or s "") #",")
       (map string/trim)
       (map string/lower-case)
       (map #(string/replace % #"^\." ""))
       (remove string/blank?)
       vec))

(defn- sh-out
  [args]
  (let [{:keys [out exit]} (apply p/sh {:out :string :err :string :continue true} args)]
    (when (zero? exit)
      (not-empty (string/trim out)))))

(defn- looks-like-bundle-id?
  [s]
  (and (string/includes? s ".")
       (not (string/includes? s "/"))
       (not (string/ends-with? s ".app"))))

(defn- bundle-id-from-app
  [app-path]
  (sh-out ["defaults" "read" (str (fs/path app-path "Contents" "Info")) "CFBundleIdentifier"]))

(defn- app-path-from-name
  [app-label]
  (sh-out ["osascript" "-e" (str "POSIX path of (path to application \"" app-label "\")")]))

(defn- app-path-from-bundle-id
  [bundle-id]
  (sh-out ["mdfind" (str "kMDItemCFBundleIdentifier == '" bundle-id "'")]))

(defn- existing-app-path
  [s]
  (let [candidates (cond-> [s]
                     (not (string/ends-with? s ".app")) (conj (str s ".app"))
                     (not (fs/absolute? s)) (into [(str "/Applications/" s)
                                                   (str "/Applications/" s ".app")]))]
    (->> candidates (map str) (filter fs/exists?) first)))

(defn resolve-app
  "Resolve app name, path, or bundle id to {:path :bundle-id :app-name}."
  [app]
  (let [app (string/trim (str app))
        path (or (existing-app-path app)
                 (when (looks-like-bundle-id? app)
                   (app-path-from-bundle-id app))
                 (app-path-from-name app))
        path (some-> path string/trim (string/replace #"/$" ""))]
    (when (and path (fs/exists? path))
      {:path path
       :bundle-id (bundle-id-from-app path)
       :app-name (fs/file-name path)})))

(defn read-info
  [app-path]
  (let [{:keys [out exit]} (p/sh {:out :string :err :string :continue true}
                                 "plutil" "-convert" "json" "-o" "-"
                                 (str (fs/path app-path "Contents" "Info.plist")))]
    (when (zero? exit)
      (json/parse-string out true))))

(defn- app-dirs
  []
  (cond-> [(fs/path "/Applications")]
    (fs/directory? (fs/path (System/getProperty "user.home") "Applications"))
    (conj (fs/path (System/getProperty "user.home") "Applications"))))

(defn- installed-apps
  []
  (->> (app-dirs)
       (mapcat fs/list-dir)
       (filter #(string/ends-with? (str %) ".app"))
       (filter fs/directory?)))

(defn- app-claim
  [app selected]
  (when-let [info (read-info (str app))]
    (let [claimed (->> (:CFBundleDocumentTypes info)
                       (mapcat :CFBundleTypeExtensions)
                       (map string/lower-case)
                       set)
          overlap (filterv selected claimed)]
      (when (seq overlap)
        {:path (str app)
         :app-name (fs/file-name app)
         :bundle-id (:CFBundleIdentifier info)
         :overlap overlap
         :coding-n (count (filter coding-extension-set claimed))}))))

(defn discover-claimants
  "Apps in /Applications (and ~/Applications) that claim any of selected."
  [selected]
  (let [selected (set selected)]
    (->> (installed-apps)
         (keep #(app-claim % selected))
         (sort-by (comp - :coding-n))
         vec)))

(defn editor-like?
  [claimant]
  (>= (:coding-n claimant) 15))

(defn current-defaults
  "Map extension -> {:path :app-name} via Launch Services. Read-only."
  [exts]
  (let [{:keys [out exit]} (apply p/sh {:out :string :err :string :continue true}
                                  "swift" "-e" defaults-swift (vec exts))]
    (when-not (zero? exit)
      (throw (ex-info "Failed to query default apps" {:babashka/exit 1 :exit exit})))
    (into {}
          (for [line (string/split-lines out)
                :let [[ext path] (string/split line #"\t" 2)]
                :when (not (string/blank? path))]
            [ext {:path (string/replace path #"/$" "")
                  :app-name (fs/file-name path)}]))))

(defn leftovers
  "Selected extensions whose current default is not the target app."
  [target defaults selected]
  (let [target-path (:path target)]
    (->> selected
         (keep (fn [ext]
                 (when-let [owner (get defaults ext)]
                   (when (not= target-path (:path owner))
                     {:ext ext
                      :path (:path owner)
                      :app-name (:app-name owner)}))))
         vec)))

(defn leftover-holders
  "Unique apps among leftover extension owners."
  [remaining]
  (->> remaining
       (map (juxt :path :app-name))
       distinct
       (mapv (fn [[path app-name]]
               {:path path :app-name app-name}))))

(defn build-plan
  "Gather claimants and decide who to demote / what will remain."
  [target selected {:keys [force]}]
  (let [claimants (discover-claimants selected)
        editors (->> claimants
                     (filter editor-like?)
                     (remove #(= (:path %) (:path target)))
                     vec)
        defaults (current-defaults selected)
        remaining (leftovers target defaults selected)
        leftover-apps (leftover-holders remaining)
        demote (if force
                 (->> (concat editors leftover-apps)
                      (group-by :path)
                      vals
                      (mapv first))
                 editors)]
    {:target target
     :selected selected
     :demote demote
     :defaults defaults
     :remaining remaining}))

(defn- lsregister!
  [flag app-path]
  (let [{:keys [exit err]} (p/sh {:out :string :err :string :continue true}
                                 lsregister-bin flag app-path)]
    {:path app-path
     :flag flag
     :exit exit
     :err (not-empty (string/trim (or err "")))}))

(defn chase-leftovers!
  "Unregister whoever still owns a selected ext, until target wins or we stall."
  [target selected]
  (loop [round 1
         seen #{}]
    (let [defaults (current-defaults selected)
          remaining (leftovers target defaults selected)
          holders (->> (leftover-holders remaining)
                       (remove #(contains? seen (:path %)))
                       vec)]
      (cond
        (empty? remaining)
        {:remaining [] :rounds (dec round)}

        (or (empty? holders) (> round 8))
        {:remaining remaining :rounds round}

        :else
        (do
          (println (str "  chase " round ": "
                        (string/join ", " (map :app-name holders))))
          (doseq [{:keys [path]} holders]
            (lsregister! "-u" path))
          (lsregister! "-f" (:path target))
          (Thread/sleep 400)
          (recur (inc round) (into seen (map :path holders))))))))

(defn apply-plan!
  [plan {:keys [force]}]
  (let [unreg (mapv #(lsregister! "-u" (:path %)) (:demote plan))
        reg (lsregister! "-f" (get-in plan [:target :path]))
        after (if force
                (chase-leftovers! (:target plan) (:selected plan))
                (let [defaults (current-defaults (:selected plan))]
                  {:remaining (leftovers (:target plan) defaults (:selected plan))}))]
    (merge {:unregistered unreg :registered reg} after)))

(defn- print-plan!
  [{:keys [target selected demote remaining]}]
  (println (str "Promote " (:app-name target) " for " (count selected) " extensions"))
  (if (seq demote)
    (doseq [app demote]
      (println (str "  demote " (:app-name app))))
    (println "  demote (none)"))
  (when (seq remaining)
    (println "  leftovers before apply:")
    (doseq [{:keys [ext app-name]} remaining]
      (println (str "    ." ext " -> " app-name)))))

(defn- print-result!
  [target {:keys [remaining]} force?]
  (if (seq remaining)
    (do
      (println (str "Still not " (:app-name target) ":"))
      (doseq [{:keys [ext app-name]} remaining]
        (println (str "  ." ext " -> " app-name)))
      (if force?
        (println "Stopped: remaining holders could not be demoted further.")
        (println "Re-run with --force to unregister leftover holders."))
      (println "Launching a demoted app will re-register it."))
    (println (str "All selected extensions now open with " (:app-name target) "."))))

(defn- ext-list-arg?
  [s]
  (when s
    (or (string/includes? s ",")
        (string/starts-with? s ".")
        (contains? coding-extension-set
                   (string/lower-case (string/replace s #"^\." ""))))))

(defn selected-extensions
  "Resolve the extension set from positional args."
  [args]
  (if-let [exts (or (second args)
                    (when (ext-list-arg? (first args))
                      (first args)))]
    (parse-extensions exts)
    coding-extensions))

(defn format-assoc-row
  [width ext app-name]
  (str (format (str "%-" width "s") ext) " " app-name))

(defn print-list!
  "Print current default app per extension, aligned like `svg Cursor.app`."
  [exts]
  (let [defaults (current-defaults exts)
        width (apply max 1 (map count exts))]
    (doseq [ext exts]
      (println (format-assoc-row width ext (get-in defaults [ext :app-name] "(none)"))))))

(defn- help-text []
  (str "Usage: bbg assoc-exts <app> [exts] [--force] [--dry-run]\n"
       "       bbg assoc-exts --list [exts]\n\n"
       "Promote <app> (name, path, or bundle id) for coding extensions.\n"
       "Without [exts], uses the 153 VS Code-style coding extensions.\n"
       "[exts] is comma-separated, e.g. clj,cljs,edn\n\n"
       "Options:\n"
       (cli/format-opts cli-spec)))

(defn exec!
  "Parse CLI args and list or promote associations."
  [args]
  (try
    (let [{:keys [opts args]} (cli/parse-args args cli-spec)
          selected (selected-extensions args)]
      (cond
        (empty? selected)
        (throw (ex-info "No extensions to associate" {:babashka/exit 1}))

        (:list opts)
        (print-list! selected)

        (not (first args))
        (do (binding [*out* *err*] (println (help-text)))
            (throw (ex-info "missing app" {:babashka/exit 1})))

        :else
        (if-let [target (resolve-app (first args))]
          (let [plan (build-plan target selected opts)]
            (print-plan! plan)
            (if (:dry-run opts)
              (println "dry-run: nothing changed")
              (print-result! target (apply-plan! plan opts) (:force opts))))
          (throw (ex-info (str "App not found: " (first args)) {:babashka/exit 1})))))
    (catch Exception e
      (if (= :org.babashka/cli (:type (ex-data e)))
        (do (binding [*out* *err*] (println (help-text)))
            (throw (ex-info "bad args" {:babashka/exit 1})))
        (throw e)))))
