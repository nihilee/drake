(ns drake.steps
  "This module implement target dependencies and selection.

  The main function is select-steps, and targets are represented
  as structured described in process-qualifiers documentation.
  The process begins by creating these target structures from target
  strings (^A, +B, -^C etc.), then matching each target against the steps
  (each target can match multiple steps, for example, in regexp matching),
  and then expanding each step according to its tree mode (down-tree, up-tree
  or this step only).

  Then this final big list of steps is processed one-by-one to make sure
  dependants are always run after their dependencies, as well as process
  exclusions (see add-step function)."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [fs.core :as fs]
            [clojure.tools.logging :refer [debug trace]]
            [slingshot.slingshot :refer [throw+]]
            [drake.utils :as utils :refer [clip reverse-multimap concat-distinct]]
            [drake.options :refer [*options*]]
            [drake.fs :refer [remove-extra-slashes normalized-path]])
  (:import [org.jgrapht.graph DirectedAcyclicGraph DefaultEdge]))

(defn step-str
  "Returns a string representation of the step as a comma-separated
   list of its outputs and tags.
   TODO(artem) tags are not supported now."
  [step]
  (str/join ", " (:outputs step)))

(defn add-dependencies
  "Given a parse tree after string substitutions, adds step dependencies to
   its steps (see parse.clj main comment).

   Also adds input and output maps to the parse-tree.

   This function is automatically invoked from parse/parse-linevs.
   It is in this module since all the functions below heavily depend
   on the state created by it."
  [raw-parse-tree]
  (trace "Calculating dependency graph...")
  (let [steps (:steps raw-parse-tree)
        ;; goes over steps and maps values returned by function f (a sequence)
        ;; into a lists of step step indexes that have them, for example, if
        ;; f returns list of inputs, the output map could be:
        ;;   { "/a" [0 2] "/b" [3] }
        step-index-map (fn [f] (reverse-multimap
                                (map-indexed vector (map f steps))))
        ;; returns a function that maps over a given key
        map-key (fn [f key] #(map f (% key)))

        input-tags-map (step-index-map :input-tags)
        output-tags-map (step-index-map :output-tags)

        method-map (step-index-map #(if-let [method (get-in % [:opts :method])]
                                      [method] []))

        normalized-input-map (step-index-map (map-key normalized-path :inputs))
        normalized-output-map (step-index-map (map-key normalized-path :outputs))

        build-variants (fn [variants] (map step-index-map variants))
        output-map-lookup-regexp
        (apply utils/merge-multimaps-distinct
               (build-variants [:raw-outputs
                                (map-key remove-extra-slashes :raw-outputs)
                                :outputs
                                (map-key remove-extra-slashes :outputs)]))
        output-map-lookup (utils/merge-multimaps-distinct output-map-lookup-regexp
                                                          normalized-output-map)
        ;;_ (prn output-map-lookup)
        ]
    (assoc raw-parse-tree
           :output-map-lookup output-map-lookup
           :output-map-lookup-regexp output-map-lookup-regexp
           :output-tags-map output-tags-map
           :method-map method-map
      ;; this basically calculates dependencies
      ;; (mapv is used to convert it to vector for indexed lookup in the future)
           :steps (mapv
                   #(assoc %
                           :parents (apply
                                     concat-distinct
                                     (concat (map normalized-output-map
                                                  (map normalized-path (:inputs %)))
                                             (map output-tags-map
                                                  (:input-tags %))))
                           :children (apply
                                      concat-distinct
                                      (concat (map normalized-input-map
                                                   (map normalized-path (:outputs %)))
                                              (map input-tags-map
                                                   (:output-tags %)))))
                   steps))))

;; No way to get to MAX_PATH from Java
;; Leave some characters for unique suffixes and for files inside
(def ^:private MAX_PATH 200)

(defn calc-step-dirs
  "Given the parse-tree, calculate each step's directory for keeping
   log and temporary files. Ensures that directory names are unique
   and not too long.

   Returns the parse tree with added :dir to each step."
  [{:keys [steps] :as parse-tree}]
  (trace "Naming steps' temporary directories...")
  (let [drake-dir (fs/absolute-path (:tmpdir *options*))]
    (if (> (count drake-dir) (dec MAX_PATH))
      (throw+ {:msg (format "workflow directory name %s is too long."
                            drake-dir)}))
    (let [cut #(subs % 0 (min (count %) MAX_PATH))
          dirs (map (fn [{:keys [raw-outputs output-tags] :as step}]
                      (cut (str drake-dir "/"
                                ;; e.g. "output1,dir1_dir2_output2,tag1"
                                (str/join "," (map #(str/replace % #"/" "_")
                                                   (concat raw-outputs
                                                           output-tags))))))
                    steps)
          ;; { "dir1" [0] "dir2" [1 2] }
          dir-indexed (reverse-multimap (map-indexed vector (map vector dirs)))]
      (reduce (fn [tree [dir steps]]
                ;; add .0, .1, etc. but only if needed,
                ;; i.e. more than 1 directory with the same name
                (let [single (= 1 (count steps))]
                  (reduce (fn [tree [count step-index]]
                            (assoc-in tree [:steps step-index :dir]
                                      (str dir (if-not single
                                                 (format ".%d" count) ""))))
                          tree (map-indexed vector steps))))
              parse-tree dir-indexed))))

;; TODO(artem): Templates are not supported now
(defn- find-target-steps
  "Given a target, returns a list of steps indexes that match this target.
   See process-qualifiers function to see how a target is prepared."
  [parse-tree {:keys [name match-type match-string]}]
  (let [[map-to-use map-to-use-regexp]
        (match-type {:tag    [:output-tags-map :output-tags-map]
                     :method [:method-map :method-map]
                     :output [:output-map-lookup :output-map-lookup-regexp]})
        dots (= match-string "...")
        regexp-search (= \@ (first match-string))
        everything (and (not regexp-search)
                        dots (= match-type :output))
        targets (if everything
                  ;; optimization for a frequent case
                  (map-indexed (fn [i _] i) (:steps parse-tree))
                  (if-not (or regexp-search dots)
                    (apply concat-distinct
                           (vals (select-keys
                                  (parse-tree map-to-use)
                                  (if-not (= match-type :output)
                                    [match-string]
                                    ;; if matching by filenames,
                                    ;; match by all variants - given,
                                    ;; slash-normalized, and full-normalized
                                    [match-string
                                     (remove-extra-slashes match-string)
                                     (normalized-path match-string)]))))
                    (let [re (if-not dots (re-pattern (clip match-string)))]
                      ;; sorting to preserve matching in the order of definition
                      (sort (mapcat (fn [[output index]]
                                      (if (or dots (re-find re output)) index))
                                    (parse-tree map-to-use-regexp))))))]
    (if-not (empty? targets)
      targets
      (throw+ {:msg (str "target not found: " name)}))))

;; TODO: do dfs use jgraphT
(defn- create-graph [tree-steps]
  (let [graph (DirectedAcyclicGraph. DefaultEdge)]
    (doseq [index (range (count tree-steps))]
      (.addVertex graph index))
    (doseq [[index step] (map-indexed vector tree-steps)
            child (:children step)]
      (.addEdge graph index child))
    graph))

(def momo-create-graph
  (memoize create-graph))

(defn- expand-step-jgrapht
  [tree-steps index up-tree valid-step-indices]
  (let [g (momo-create-graph tree-steps)]
    (if up-tree
      (cond
        (nil? valid-step-indices) (conj
                                   (into #{} (.getAncestors g index))
                                   index)
        (valid-step-indices index) (set/intersection
                                    valid-step-indices
                                    (conj
                                     (into #{} (.getAncestors g index))
                                     index))
        :else [])
      (cond
        (nil? valid-step-indices) (conj
                                   (into #{} (.getDescendants g index))
                                   index)
        (valid-step-indices index) (set/intersection
                                    valid-step-indices
                                    (conj
                                     (into #{} (.getDescendants g index))
                                     index))
        :else []))))

(defn- expand-step-recur
  "The recursive function used in expand-step (see below).
   current-chain is the current set of targets and is used
   in cycle detection.

   current-chain is a vector right now, a hashset would be faster,
   but I would like to preserve the order for being able to print
   a nice error message.

   valid-step-indices is a set of steps that are valid for expansion.
   nil means that all steps are valid for expansion."
  [tree-steps index up-tree current-chain valid-step-indices]
  ;;(prn index)

  ;; Check if current step is in list of valid steps
  (if (or (not valid-step-indices) (valid-step-indices index))
    (let [step (tree-steps index)
          current-chain-and-me (conj current-chain index)]
      (when (some #{index} current-chain)
        (throw+ {:msg (str "cycle dependency detected: "
                           (str/join " -> " (map #(step-str (tree-steps %))
                                                 current-chain-and-me)))}))
      (let [all-but-me (mapcat #(expand-step-recur tree-steps %
                                                   up-tree current-chain-and-me valid-step-indices)
                               (step (if up-tree
                                       :parents
                                       :children)))]
        ;; conj appends an element to the back of the vector (which
        ;; is what we need for up-tree mode), but to the beginning
        ;; of the sequence (which is what we need for down-tree)
        (conj (if up-tree
                (into [] all-but-me)
                all-but-me)
              index)))
    []))

(defn expand-step-restricted
  "Like expand-step below, but only expand the steps that are in the
   valid-step-indices.  If valid-step-indices is nil, expand them all"
  [parse-tree index tree-mode valid-step-indices]
  ;;(prn (parse-tree :steps))
  (if (= tree-mode :only)
    [index]
    (expand-step-jgrapht (:steps parse-tree) index (nil? tree-mode) valid-step-indices)))

(defn expand-step
  "Given a step index, return an ordered list of all steps involved
   into building the given step.

   Tree mode can be either :down, :only or nil for default (up-tree)."
  [parse-tree index tree-mode]
  (expand-step-restricted parse-tree index tree-mode nil))

(defn- clip-only
  [str charset]
  "Removes the first character from the string, iff it is present
   in charset (a set of characters), and returns a tuple:
     [ new-string, removed-character ]

   If the first character of the string is not present in charset,
   returns [str nil]"
  (let [ch (first str)]
    (if (charset ch)
      [(clip str) ch]
      [str nil])))

(defn- process-qualifiers
  "Given a list of target names, calculates build and tree modes
   for them. Returns a list of structures of the following format:

   { :name '%@target'        ;; target name stripped out of tree and build modes,
                             ;; could still be a partial name or a regexp,
                             ;; and have tag and method symbols
     :tree  :down            ;; :down, :only, or nil for default (up)
     :build :exclude         ;; :forced, :exclude or nil for default
                             ;; (timestamped)
     :match-type :tag        ;; :output, :tag or :method
     :match-string '@target' ;; the final match string (no tag/method symbols)
   }"
  [target-names]
  (map #(let [[clipped-name build-mode] (clip-only % #{\+ \-})
              [clipped-name tree-mode]  (clip-only clipped-name #{\^ \=})
              [match-type match-string]
              (cond
                (= \% (first clipped-name))
                [:tag (clip clipped-name)]
                (= "()" (subs clipped-name
                              (max 0 (- (count clipped-name) 2))))
                [:method (subs clipped-name 0
                               (- (count clipped-name) 2))]
                :else
                [:output clipped-name])]
          {:name  clipped-name
           :build ({\+ :forced \- :exclude} build-mode)
           :tree  ({\= :only \^ :down} tree-mode)
           :match-type match-type
           :match-string match-string})
       target-names))

(defn- match-target-steps
  "Given the list of targets returned by process-qualifiers,
   expands each target (which can match several steps)
   into one or more records which have
   :index instead of :name, but inherit the same build and tree modes."
  [parse-tree targets]
  (mapcat (fn [{:keys [build tree match-type] :as target}]
            (map #(hash-map :index %
                            :build build
                            :tree tree
                            :match-type match-type)
                 (find-target-steps parse-tree target)))
          targets))

(defn- expand-targets
  "Given the list of targets with indexes as returned by
   match-target-steps, expands targets down- or up-tree as specified.
   Tree-mode is no longer relevant, but all new targets inherit the
   build mode."
  [parse-tree targets]
  (mapcat (fn [{:keys [index build tree match-type] :as target}]
            (map #(hash-map :index %
                            :build build
                            ;; only the originally specified step preserves
                            ;; match-type, not its dependencies
                            :match-type (if (= index %)
                                          match-type
                                          :output))
                 (expand-step parse-tree index tree)))
          targets))

(def all-dependencies-func
  (memoize (fn [parse-tree]
             (memoize #(into #{} (expand-step parse-tree % :down))))))

(defn all-dependencies
  "Expands the step and all its dependencies downwards.
   Uses two-level memoization in order to avoid iterating
   over the whole parse-tree to calculate its hash every time.
   The resulted function takes two arguments: parse-tree and step's index."
  [parse-tree step]
  ((all-dependencies-func parse-tree) step))

(defn- add-step
  "We will be creating a list of steps by starting with an empty list
   and adding steps to it one by one. current-steps-map represents the
   accumualted list of steps so far, and pos is the numeric position of the
   current step being added (new-step). For each new step:

     1) if new-step is in the exclusion mode (-A), remove the step it refers
        to from the already processed steps (current-steps-map)
     2) if new-step refers to a step already existing in the accumulated list,
        set the forced-build mode to be an OR of the two
     3) if there's one ore more steps in the accumulated list (current-steps-map)
        which is a direct or indirect dependant of new-step, find the first
        such step and insert new-step before it."
  [parse-tree
   [current-steps-map pos]
   {:keys [index build match-type] :as new-step}]
  (let [exclusion (= build :exclude)]
    (if-let [existing-step (current-steps-map index)]
      ;; step already exists
      (if exclusion
        ;; exclude, or...
        [(dissoc current-steps-map index) (inc pos)]
        ;; ...update
        (let [{old-build :build old-match-type :match-type} existing-step]
          [(assoc current-steps-map index
                  ;; update some fields, but keep :pos as it was
                  (assoc existing-step
                         :build (if (or (= build :forced)
                                        (= old-build :forced))
                                  :forced)
                         ;; same logic for tag and methods as for build -
                         ;; if it's specified directly, the step will be built
                         ;; (method takes precedence here)
                         :match-type (some (hash-set old-match-type match-type)
                                           [:method :tag :output])))
           (inc pos)]))
      ;; step doesn't exist
      (if exclusion
        ;; if it's exclusion, safe to ignore
        [current-steps-map (inc pos)]
        (let [dependencies (all-dependencies parse-tree index)
              ;; dependencies of the current step already specified
              used-dependencies (set/intersection
                                 dependencies
                                 (into #{} (keys current-steps-map)))
              ;; find the very first step which is a dependency (by :pos)
              ;; and set the position of the new step to be just slightly less
              insert-position (if (empty? used-dependencies)
                                pos
                                ;; this should be enough for a million steps
                                (- (apply min (for [dep used-dependencies]
                                                (get-in current-steps-map [dep :pos])))
                                   0.0000001))]
          ;; add a new step to the map, with a new field specifying its
          ;; order (:pos)
          [(assoc current-steps-map index (assoc new-step :pos insert-position))
           (inc pos)])))))

(defn- check-output-conflicts
  "Checks that all selected steps have unique outputs.
   Returns the steps given or throws an exception."
  [parse-tree steps]
  (let [output-counts (frequencies (for [{:keys [index]} steps
                                         output (get-in parse-tree [:steps index :outputs])]
                                     (normalized-path output)))
        repeated-outputs (for [[output n] output-counts
                               :when (> n 1)]
                           output)]
    (when (seq repeated-outputs)
      (throw+ {:msg (str "duplicated outputs: "
                         (str/join ", " repeated-outputs))})))
  steps)

(defn select-steps
  "Given a parse tree and an array of target selection expressions,
   returns an ordered list of step indexes to be run."
  [parse-tree target-names]
  (utils/with-time-elapsed
    (utils/in-ms debug "Selecting steps")
    (let [steps (expand-targets parse-tree
                                (match-target-steps
                                 parse-tree
                                 (process-qualifiers target-names)))]
      (check-output-conflicts
       parse-tree
       (sort-by :pos
                (vals (first (reduce #(add-step parse-tree %1 %2)
                                     [{} 0] steps))))))))
