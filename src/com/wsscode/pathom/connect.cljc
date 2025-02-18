(ns com.wsscode.pathom.connect
  #?(:cljs [:require-macros com.wsscode.pathom.connect])
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.parser :as pp]
            [com.wsscode.pathom.trace :as pt]
            [com.wsscode.pathom.misc :as p.misc]
            [com.wsscode.common.combinatorics :as combo]
            [#?(:clj  com.wsscode.common.async-clj
                :cljs com.wsscode.common.async-cljs)
             :as p.async
             :refer [let-chan let-chan* go-promise go-catch <? <?maybe <!maybe]]
            [clojure.set :as set]
            [clojure.core.async :as async :refer [<! >! go put!]]
            [edn-query-language.core :as eql]))

(defn atom-with [spec]
  (s/with-gen p/atom? #(gen/fmap atom (s/gen spec))))

(when p.misc/INCLUDE_SPECS
  (s/def ::sym symbol?)
  (s/def ::sym-set (s/coll-of ::sym :kind set?))
  (s/def ::attribute (s/or :attribute ::p/attribute :set ::attributes-set))
  (s/def ::attributes-set (s/coll-of ::p/attribute :kind set?))
  (s/def ::batch? boolean?)

  (s/def ::resolve fn?)
  (s/def ::mutate fn?)

  (s/def ::resolver (s/keys :opt [::sym ::input ::output ::params ::resolve]))
  (s/def ::mutation (s/keys :opt [::sym ::input ::output ::params ::mutate]))

  (s/def ::idents ::attributes-set)
  (s/def ::input ::attributes-set)
  (s/def ::out-attribute (s/or :plain ::attribute :composed (s/map-of ::attribute ::output)))
  (s/def ::output (s/or :attribute-list (s/coll-of ::out-attribute :kind vector? :min-count 1)
                        :union (s/map-of ::attribute ::output)))
  (s/def ::params ::output)

  (s/def ::resolver-data (s/keys :req [::sym] :opt [::input ::output ::cache?]))
  (s/def ::resolver-weights (atom-with (s/map-of ::sym number?)))

  (s/def ::index-resolvers (s/map-of ::sym ::resolver-data))

  (s/def ::mutation-data (s/keys :req [::sym] :opt [::params ::output]))
  (s/def ::mutations (s/map-of ::sym ::resolver-data))

  (s/def ::io-map (s/map-of ::attribute ::io-map))
  (s/def ::index-io (s/map-of ::attributes-set ::io-map))

  (s/def ::attribute-paths (s/map-of ::attributes-set (s/coll-of ::sym :kind set?)))
  (s/def ::index-oir (s/map-of ::attribute ::attribute-paths))

  (s/def ::indexes (s/keys :opt [::index-resolvers ::index-io ::index-oir ::idents ::index-mutations]))

  (s/def ::dependency-track (s/coll-of (s/tuple ::sym-set ::attributes-set) :kind set?))

  (s/def ::resolver-dispatch ifn?)
  (s/def ::mutate-dispatch ifn?)

  (s/def ::mutation-join-globals (s/coll-of ::attribute))

  (s/def ::attr-input-in ::sym-set)
  (s/def ::attr-output-in ::sym-set)

  (s/def ::attr-reach-via-simple-key ::input)
  (s/def ::attr-reach-via-deep-key (s/cat :input ::input :path (s/+ ::attribute)))
  (s/def ::attr-reach-via-key (s/or :simple ::attr-reach-via-simple-key
                                    :deep ::attr-reach-via-deep-key))
  (s/def ::attr-reach-via (s/map-of ::attr-reach-via-key ::sym-set))

  (s/def ::attr-provides-key (s/or :simple ::attribute
                                   :deep (s/coll-of ::attribute :min-count 2 :kind vector?)))
  (s/def ::attr-provides (s/map-of ::attr-provides-key ::sym-set))

  (s/def ::attr-combinations (s/coll-of ::attributes-set :kind set?))

  (s/def ::attribute-info
    (s/keys :opt [::attr-input-in
                  ::attr-combinations
                  ::attr-reach-via
                  ::attr-output-in]))

  (s/def ::index-attributes
    (s/map-of (s/or :simple ::attribute
                    :global #{#{}}
                    :multi ::input) ::attribute-info))

  (s/def ::index-mutations
    (s/map-of ::sym ::mutation-data))

  (s/def ::map-resolver
    (s/merge ::resolver-data (s/keys :req [::output ::resolve])))

  (s/def ::map-mutation
    (s/merge ::mutation-data (s/keys :req [::mutate])))

  (s/def ::map-operation
    (s/or :resolver ::map-resolver :mutation ::map-mutation))

  (s/def ::register
    (s/or :operation ::map-operation
          :operations (s/coll-of ::register)))

  (s/def ::path-coordinate (s/tuple ::attribute ::sym))
  (s/def ::plan-path (s/coll-of ::path-coordinate))
  (s/def ::plan (s/coll-of ::plan-path))
  (s/def ::sort-plan (s/fspec :args (s/cat :env ::p/env :plan ::plan-path)))
  (s/def ::transform fn?))

(defn resolver-data
  "Get resolver map information in env from the resolver sym."
  [env-or-indexes sym]
  (let [idx (cond-> env-or-indexes
              (contains? env-or-indexes ::indexes)
              ::indexes)]
    (get-in idx [::index-resolvers sym])))

(defn mutation-data
  "Get mutation map information in env from the resolver sym."
  [env-or-indexes sym]
  (let [idx (cond-> env-or-indexes
              (contains? env-or-indexes ::indexes)
              ::indexes)]
    (get-in idx [::index-mutations sym])))

(defn- flat-query [query]
  (if (map? query)
    (apply concat (map flat-query (vals query)))
    (->> query p/query->ast :children (mapv :key))))

(defn- merge-io-attrs [a b]
  (cond
    (and (map? a) (map? b))
    (merge-with merge-io-attrs a b)

    (map? a) a
    (map? b) b

    :else b))

(defn- normalize-io [output]
  (if (map? output) ; union
    (let [unions (into {} (map (fn [[k v]]
                                 [k (normalize-io v)]))
                       output)
          merged (reduce merge-io-attrs (vals unions))]
      (assoc merged ::unions unions))
    (into {} (map (fn [x] (if (map? x)
                            (let [[k v] (first x)]
                              [k (normalize-io v)])
                            [x {}])))
          output)))

(defn merge-io
  "Merge ::index-io maps."
  [a b]
  (merge-with merge-io-attrs a b))

(defn merge-oir
  "Merge ::index-oir maps."
  [a b]
  (merge-with #(merge-with into % %2) a b))

(defn merge-grow [a b]
  (cond
    (and (set? a) (set? b))
    (set/union a b)

    (and (map? a) (map? b))
    (merge-with merge-grow a b)

    (nil? b) a

    :else
    b))

(defmulti index-merger
  "This is an extensible gateway so you can define different strategies for merging different
  kinds of indexes."
  (fn [k _ _] k))

(defmethod index-merger ::index-io [_ ia ib]
  (merge-io ia ib))

(defmethod index-merger ::index-oir [_ ia ib]
  (merge-oir ia ib))

(defmethod index-merger ::index-attributes [_ a b]
  (merge-grow a b))

(defmethod index-merger :default [_ a b]
  (merge-grow a b))

(defn merge-indexes [ia ib]
  (reduce-kv
    (fn [idx k v]
      (if (contains? idx k)
        (update idx k #(index-merger k % v))
        (assoc idx k v)))
    ia ib))

(defn output-provides* [{:keys [key children]}]
  (let [children (if (some-> children first :type (= :union))
                   (mapcat :children (-> children first :children))
                   children)]
    (cond-> [key]
      (seq children)
      (into (mapcat (comp
                      (fn [x]
                        (mapv #(vec (flatten (vector key %))) x))
                      #(output-provides* %))) children))))

(defn output-provides [query]
  (if (map? query)
    (into [] (mapcat output-provides) (vals query))
    (into [] (mapcat output-provides*) (:children (eql/query->ast query)))))

(defn normalized-children [{:keys [children]}]
  (if (some-> children first :type (= :union))
    (mapcat :children (-> children first :children))
    children))

(defn index-attributes [{::keys [sym input output]}]
  (let [provides      (remove #(contains? input %) (output-provides output))
        sym-group     #{sym}
        attr-provides (zipmap provides (repeat sym-group))
        input-count   (count input)]
    (as-> {} <>
      ; inputs
      (reduce
        (fn [idx in-attr]
          (update idx in-attr merge
            {::attribute     in-attr
             ::attr-provides attr-provides
             ::attr-input-in sym-group}))
        <>
        (case input-count
          0 [#{}]
          1 input
          [input]))

      ; combinations
      (if (> input-count 1)
        (reduce
          (fn [idx in-attr]
            (update idx in-attr merge
              {::attribute         in-attr
               ::attr-combinations #{input}
               ::attr-input-in     sym-group}))
          <>
          input)
        <>)

      ; provides
      (reduce
        (fn [idx out-attr]
          (if (vector? out-attr)
            (update idx (peek out-attr) (partial merge-with merge-grow)
              {::attribute      (peek out-attr)
               ::attr-reach-via {(into [input] (pop out-attr)) sym-group}
               ::attr-output-in sym-group})

            (update idx out-attr (partial merge-with merge-grow)
              {::attribute      out-attr
               ::attr-reach-via {input sym-group}
               ::attr-output-in sym-group})))
        <>
        provides)

      ; leaf / branches
      (reduce
        (fn [idx {:keys [key children]}]
          (cond-> idx
            key
            (update key (partial merge-with merge-grow)
              {(if children ::attr-branch-in ::attr-leaf-in) sym-group})))
        <>
        (if (map? output)
          (mapcat #(tree-seq :children normalized-children (eql/query->ast %)) (vals output))
          (tree-seq :children :children (eql/query->ast output)))))))

(defn add
  "Low level function to add resolvers to the index. This function adds the resolver
  configuration to the index set, adds the resolver to the ::pc/index-resolvers, add
  the output to input index in the ::pc/index-oir and the reverse index for auto-complete
  to the index ::pc/index-io.

  This is a low level function, for adding to your index prefer using `pc/register`."
  ([indexes sym] (add indexes sym {}))
  ([indexes sym sym-data]
   (let [{::keys [input output] :as sym-data} (merge {::sym   sym
                                                      ::input #{}}
                                                     sym-data)]
     (let [input' (if (and (= 1 (count input))
                           (contains? (get-in indexes [::index-io #{}]) (first input)))
                    #{}
                    input)]
       (merge-indexes indexes
         (cond-> {::index-resolvers  {sym sym-data}
                  ::index-attributes (index-attributes sym-data)
                  ::index-io         {input' (normalize-io output)}
                  ::index-oir        (reduce (fn [indexes out-attr]
                                               (cond-> indexes
                                                 (not= #{out-attr} input)
                                                 (update-in [out-attr input] (fnil conj #{}) sym)))
                                       {}
                                       (flat-query output))}
           (= 1 (count input'))
           (assoc ::idents #{(first input')})))))))

(defn add-mutation
  [indexes sym {::keys [params output] :as data}]
  (merge-indexes indexes
    {::index-mutations  {sym (assoc data ::sym sym)}
     ::index-attributes (as-> {} <>
                          (reduce
                            (fn [idx attribute]
                              (update idx attribute (partial merge-with merge-grow)
                                {::attribute              attribute
                                 ::attr-mutation-param-in #{sym}}))
                            <>
                            (some-> params eql/query->ast p/ast-properties))

                          (reduce
                            (fn [idx attribute]
                              (update idx attribute (partial merge-with merge-grow)
                                {::attribute               attribute
                                 ::attr-mutation-output-in #{sym}}))
                            <>
                            (some-> output eql/query->ast p/ast-properties)))}))

(defn register
  "Updates the index by registering the given resolver or mutation (lets call it item),
  an item can be:

  1. a resolver map
  2. a mutation map
  3. a sequence with items

  The sequence version can have nested sequences, they will be recursively add.

  Examples of possible usages:

      (-> {} ; blank index
          (pc/register one-resolver) ; single resolver
          (pc/register one-mutation) ; single mutation
          (pc/register [one-resolver one-mutation]) ; sequence of resolvers/mutations
          (pc/register [[resolver1 resolver2] [resolver3 mutation]]) ; nested sequences
          (pc/register [[resolver1 resolver2] resolver-out [resolver3 mutation]]) ; all mixed
          )
  "
  [indexes item-or-items]
  (if (sequential? item-or-items)
    (reduce
      register
      indexes
      item-or-items)

    (cond
      (::resolve item-or-items)
      (add indexes (::sym item-or-items) item-or-items)

      (::mutate item-or-items)
      (add-mutation indexes (::sym item-or-items) item-or-items))))

(defn sort-resolvers [{::p/keys [request-cache]} resolvers e]
  (->> resolvers
       (sort-by (fn [s]
                  (if request-cache
                    (if (contains? @request-cache [s e])
                      0
                      1)
                    1)))))

(defn pick-resolver
  "DEPRECATED"
  [{::keys [indexes dependency-track]
    :as    env}]
  (let [k (-> env :ast :key)
        e (p/entity env)]
    (if-let [attr-resolvers (get-in indexes [::index-oir k])]
      (let [r (->> attr-resolvers
                   (map (fn [[attrs sym]]
                          (let [missing (set/difference attrs (set (keys e)))]
                            {:sym     sym
                             :attrs   attrs
                             :missing missing})))
                   (sort-by (comp count :missing)))]
        (loop [[{:keys [sym attrs]} & t :as xs] r]
          (if xs
            (if-not (contains? dependency-track [sym attrs])
              (let [e       (try
                              (->> (p/entity (-> env
                                                 (assoc ::p/fail-fast? true)
                                                 (update ::dependency-track (fnil conj #{}) [sym attrs])) attrs)
                                   (p/elide-items p/break-values))
                              (catch #?(:clj Throwable :cljs :default) _ {}))
                    missing (set/difference (set attrs) (set (keys e)))]
                (if (seq missing)
                  (recur t)
                  (let [e (select-keys e attrs)]
                    {:e e
                     :s (first (sort-resolvers env sym e))}))))))))))

(defn async-pick-resolver
  "DEPRECATED"
  [{::keys [indexes dependency-track] :as env}]
  (go-catch
    (let [k (-> env :ast :key)
          e (p/entity env)]
      (if-let [attr-resolvers (get-in indexes [::index-oir k])]
        (let [r (->> attr-resolvers
                     (map (fn [[attrs sym]]
                            (let [missing (set/difference attrs (set (keys e)))]
                              {:sym     sym
                               :attrs   attrs
                               :missing missing})))
                     (sort-by (comp count :missing)))]
          (loop [[{:keys [sym attrs]} & t :as xs] r]
            (if xs
              (if-not (contains? dependency-track [sym attrs])
                (let [e       (try
                                (->> (p/entity (-> env
                                                   (assoc ::p/fail-fast? true)
                                                   (update ::dependency-track (fnil conj #{}) [sym attrs])) attrs)
                                     <?
                                     (p/elide-items p/break-values))
                                (catch #?(:clj Throwable :cljs :default) _ {}))
                      missing (set/difference (set attrs) (set (keys e)))]
                  (if (seq missing)
                    (recur t)
                    {:e (select-keys e attrs)
                     :s (first (sort-resolvers env sym e))}))))))))))

(defn default-resolver-dispatch [{{::keys [sym] :as resolver} ::resolver-data :as env} entity]
  #?(:clj
     (if-let [f (resolve sym)]
       (f env entity)
       (throw (ex-info "Can't resolve symbol" {:resolver resolver})))

     :cljs
     (throw (ex-info "Default resolver-dispatch is not supported on CLJS, please implement ::p.connect/resolver-dispatch in your parser environment." {}))))

(defn resolver-dispatch
  "Helper method that extract resolver symbol from env. It's recommended to use as a dispatch method for creating
  multi-methods for resolver dispatch."
  ([env] (get-in env [::resolver-data ::sym]))
  ([env _]
   (get-in env [::resolver-data ::sym])))

(defn resolver-dispatch-embedded
  "This dispatch method will fire the resolver by looking at the ::pc/resolve
  key in the resolver map details."
  [{{::keys [resolve sym]} ::resolver-data :as env} entity]
  (assert resolve (str "Can't find resolve fn for " sym))
  (resolve env entity))

#?(:clj
   (defn create-thread-pool
     "Returns a channel that will enqueue and execute messages using a thread pool.

     The returned channel here can be used as the ::pc/pool-chan argument to be used
     for executing resolvers (and avoid blocking the limited go block threads).

     You must provide the channel ch that will be used to listen for commands.
     You may provide a thread-count, if you do a fixed size thread will be created
     and shared across all calls to the parser. In case you don't provide the
     thread-count the threads will be managed by core.async built-in thread pool
     for threads (not the same as the go block threads).
     "
     ([ch]
      (async/go
        (loop []
          (when-let [{:keys [f out]} (async/<! ch)]
            (async/thread
              (try
                (if-let [x (com.wsscode.common.async-clj/<!!maybe (f))]
                  (async/put! out x)
                  (async/close! out))
                (catch Throwable e (async/put! out e))))
            (recur))))
      ch)
     ([thread-count ch]
      (doseq [_ (range thread-count)]
        (async/thread
          (loop []
            (when-let [{:keys [f out]} (async/<!! ch)]
              (try
                (if-let [x (com.wsscode.common.async-clj/<!!maybe (f))]
                  (async/put! out x)
                  (async/close! out))
                (catch Throwable e (async/put! out e)))
              (recur)))))
      ch)))

(defn step-weight [value new-value]
  (* (+ (or value 0) new-value) 0.5))

(defn update-resolver-weight [{::keys [resolver-weights]} resolver & args]
  (if resolver-weights
    (apply swap! resolver-weights update resolver args)))

(defn call-resolver*
  [{::keys [resolver-dispatch resolver-weights]
    :or    {resolver-dispatch default-resolver-dispatch}
    :as    env}
   entity]
  (let [resolver-sym (-> env ::resolver-data ::sym)
        tid          (pt/trace-enter env {::pt/event   ::call-resolver
                                          ::pt/label   resolver-sym
                                          :key         (-> env :ast :key)
                                          ::sym        resolver-sym
                                          ::input-data entity})
        start        (pt/now)]
    (let-chan* [x (try
                    (p/exec-plugin-actions env ::wrap-resolve resolver-dispatch env entity)
                    (catch #?(:clj Throwable :cljs :default) e e))]
      (if resolver-weights
        (swap! resolver-weights update resolver-sym step-weight (- (pt/now) start)))
      (pt/trace-leave env tid (cond-> {::pt/event ::call-resolver}
                                (p.async/error? x) (assoc ::p/error (p/process-error env x))))
      (p.async/throw-err x))))

(defn call-resolver [{::keys [pool-chan]
                      :as    env}
                     entity]
  (if (seq (filter #(contains? p/break-values (second %)) entity))
    (throw (ex-info "Insufficient resolver input" {:entity entity}))
    (if pool-chan
      (let [out (async/promise-chan)]
        (go
          (let [tid (pt/trace-enter env {::pt/event   ::schedule-resolver
                                         ::pt/label   (-> env ::resolver-data ::sym)
                                         :key         (-> env :ast :key)
                                         ::sym        (-> env ::resolver-data ::sym)
                                         ::input-data entity})]
            (>! pool-chan {:out out
                           :f   #(do
                                   (pt/trace-leave env tid {::pt/event ::schedule-resolver})
                                   (try
                                     (call-resolver* env entity)
                                     (catch #?(:clj Throwable :cljs :default) e e)))})))
        out)
      (call-resolver* env entity))))

(defn- entity-select-keys [env entity input]
  (let [entity (p/maybe-atom entity)]
    (let-chan [e (if (set/subset? input entity)
                   entity
                   (p/entity (-> env
                                 (assoc ::p/entity entity)
                                 (dissoc ::pp/waiting ::pp/key-watchers)) (vec input)))]
      (select-keys e input))))

(defn all-values-valid? [m input]
  (and (every? (fn [[_ v]] (not (p/break-values v))) m)
       (every? m input)))

(defn- cache-batch [env resolver-sym linked-results]
  (let [params (p/params env)]
    (doseq [[input value] linked-results]
      (p/cached env [resolver-sym input params] value))))

;; resolve plan

(defn output->provides [output]
  (let [ast (p/query->ast output)]
    (into #{} (map :key) (:children ast))))

(defn- distinct-by
  "Returns a lazy sequence of the elements of coll, removing any elements that
  return duplicate values when passed to a function f."
  ([f]
   (fn [rf]
     (let [seen (volatile! #{})]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result x]
          (let [fx (f x)]
            (if (contains? @seen fx)
              result
              (do (vswap! seen conj fx)
                  (rf result x)))))))))
  ([f coll]
   (let [step (fn step [xs seen]
                (lazy-seq
                  ((fn [[x :as xs] seen]
                     (when-let [s (seq xs)]
                       (let [fx (f x)]
                         (if (contains? seen fx)
                           (recur (rest s) seen)
                           (cons x (step (rest s) (conj seen fx)))))))
                    xs seen)))]
     (step coll #{}))))

(defn compute-paths* [index-oir keys bad-keys attr pending]
  (if (contains? index-oir attr)
    (reduce-kv
      (fn [paths input resolvers]
        (if (or (some bad-keys input)
                (contains? input attr)
                (and (seq input) (every? pending input)))
          paths
          (let [new-paths (into #{} (map #(vector [attr %])) resolvers)
                missing   (set/difference input keys pending)]
            (if (seq missing)
              (let [missing-paths
                    (->> missing
                         (into #{}
                               (map #(compute-paths*
                                       index-oir
                                       keys
                                       bad-keys
                                       %
                                       (conj pending %))))
                         (apply combo/cartesian-product)
                         (mapv #(reduce (fn [acc x] (into acc x)) (first %) (next %))))]
                (if (seq missing-paths)
                  (into paths (->> (combo/cartesian-product new-paths missing-paths)
                                   (mapv #(reduce (fn [acc x] (into acc x)) (first %) (next %)))))
                  paths))
              (into paths new-paths)))))
      #{}
      (get index-oir attr))
    #{}))

(defn compute-paths
  "This function will return a set of possible paths given a set of available keys to reach some attribute. You also
  send a set of bad keys, bad keys mean information you cannot use (maybe they already got an error, or you known will
  not be available)."
  [index-oir keys bad-keys attr]
  (into #{}
        (map (comp #(distinct-by second %)
                   #(distinct-by first %)
                   rseq))
        (compute-paths* index-oir keys bad-keys attr #{attr})))

(defn split-good-bad-keys [entity]
  (let [{bad-keys  true
         good-keys false} (group-by #(contains? p/break-values (second %)) entity)
        good-keys (into #{} (map first) good-keys)
        bad-keys  (into #{} (map first) bad-keys)]
    [good-keys bad-keys]))

(defn path-cost [{::keys   [resolver-weights]
                  ::p/keys [request-cache]
                  :as      env} path]
  (let [weights (or (some-> resolver-weights deref) {})]
    (transduce (map (fn [sym]
                      (let [e (select-keys (p/entity env) (-> (resolver-data env sym)
                                                              ::input))]
                        (if (and request-cache (contains? @request-cache [sym e]))
                          1
                          (get weights sym 1))))) + (distinct path))))

(defn default-sort-plan [env plan]
  (sort-by #(path-cost env (map second %)) plan))

(defn resolve-plan [{::keys [indexes sort-plan] :as env}]
  (let [key (-> env :ast :key)
        sort-plan (or sort-plan default-sort-plan)
        [good-keys bad-keys] (split-good-bad-keys (p/entity env))]
    (->> (compute-paths (::index-oir indexes) good-keys bad-keys key)
         (sort-plan env))))

(defn resolver->output [env resolver-sym]
  (let [{::keys [output compute-output]} (get-in env [::indexes ::index-resolvers resolver-sym])]
    (cond
      compute-output (compute-output env)
      output output
      :else (throw (ex-info "No output available" {::sym resolver-sym})))))

(defn plan->provides [env plan]
  (into #{} (mapcat #(output->provides (resolver->output env (second %)))) plan))

(defn plan->resolvers [plan]
  (->> plan
       (flatten)
       (into #{} (filter symbol?))))

(defn decrease-path-costs [{::keys [resolver-weights resolver-weight-decrease-amount]
                            :or    {resolver-weight-decrease-amount 1}} plan]
  (if resolver-weights
    (swap! resolver-weights
      #(reduce
         (fn [rw rsym]
           (assoc rw rsym (max 1 (- (get rw rsym 0) resolver-weight-decrease-amount))))
         %
         (plan->resolvers plan)))))

(defn reader-compute-plan [env failed-resolvers]
  (let [plan-trace-id (pt/trace-enter env {::pt/event ::compute-plan})
        plan          (->> (resolve-plan env)
                           (remove #(some failed-resolvers (map second %))))]
    (if (seq plan)
      (let [plan' (first plan)
            out   (plan->provides env plan')]
        (pt/trace-leave env plan-trace-id {::pt/event ::compute-plan ::plan plan ::pp/provides out})
        (decrease-path-costs env plan)
        [plan' out])
      (do
        (pt/trace-leave env plan-trace-id {::pt/event ::compute-plan})
        nil))))

(defn project-query-attributes
  "Returns a set containing all attributes that are expected to participate in path
  resolution given a query. This function is intended to help dynamic
  resolvers that need to know which attributes are required before doing a call to the
  information source. For example, we never want to issue more than one GraphQL query
  to the same server at the same query level, but if we just look at the parent query
  is not enough; that's because some of the attributes might require other attributes
  to be fetched, this function will scan the attributes and figure everything that is
  required so you can issue a single request.

  Please note the attribute calculation might depend on the data currently available
  in the `::p/entity`, if you are calculating attributes for a different context
  you might want to replace some of the entity data.

  This function is intended to be called during resolver code."
  [env query]
  (let [children (->> query (p/lift-placeholders env) p/query->ast :children)]
    (->> (reduce
           (fn [{:keys [provided] :as acc} {:keys [key]}]
             (if (contains? provided key)
               (update acc :items conj key)
               (if-let [plan (first (resolve-plan (assoc-in env [:ast :key] key)))]
                 (-> acc
                     (update :items into (or (some->> plan first second (resolver-data env) ::input)))
                     (update :items into (map first) plan)
                     (update :provided into (plan->provides env plan)))
                 (update acc :items conj key))))
           {:items #{}
            :provided #{}}
           children)
         :items)))

(defn project-parent-query-attributes
  "Project query attributes for the parent query. See"
  [{::p/keys [parent-query] :as env}]
  (project-query-attributes env parent-query))

;; readers

(defn reader
  "DEPRECATED: use reader2 instead

  Connect reader, this reader will lookup the given key in the index
  to process it, in case the resolver input can't be satisfied it will
  do a recursive lookup trying to find the next input.

  I recommend you switch to reader2, which instead plans ahead of time
  the full path it will need to cover to go from the current data to
  the requested attribute."
  [{::keys   [indexes] :as env
    ::p/keys [processing-sequence]}]
  (let [k (-> env :ast :key)
        p (p/params env)]
    (if (get-in indexes [::index-oir k])
      (if-let [{:keys [e s]} (pick-resolver env)]
        (let [{::keys [cache? batch? input] :or {cache? true} :as resolver}
              (resolver-data env s)
              env      (assoc env ::resolver-data resolver)
              response (if cache?
                         (p.async/throw-err
                           (p/cached env [s e p]
                             (if (and batch? processing-sequence)
                               (let [items          (->> processing-sequence
                                                         (mapv #(entity-select-keys env % input))
                                                         (filterv #(all-values-valid? % input))
                                                         (distinct))
                                     batch-result   (call-resolver env items)
                                     linked-results (zipmap items batch-result)]
                                 (cache-batch env s linked-results)
                                 (get linked-results e))
                               (call-resolver env e))))
                         (call-resolver env e))
              env'     (get response ::env env)
              response (dissoc response ::env)]
          (if-not (or (nil? response) (map? response))
            (throw (ex-info "Response from resolver must be a map." {:sym s :response response})))
          (p/swap-entity! env' #(merge response %))
          (let [x (get response k)]
            (cond
              (sequential? x)
              (->> (mapv atom x) (p/join-seq env'))

              (nil? x)
              (if (contains? response k)
                nil
                ::p/continue)

              :else
              (p/join (atom x) env'))))
        ::p/continue)
      ::p/continue)))

(defn- process-simple-reader-response [{:keys [query] :as env} response]
  (let [key (-> env :ast :key)
        x   (get response key)]
    (cond
      (and query (sequential? x))
      (->> (mapv atom x) (p/join-seq env))

      (nil? x)
      (if (contains? response key)
        nil
        ::p/continue)

      :else
      (p/join (atom x) env))))

(defn reader2
  [{::keys   [indexes max-resolver-weight]
    ::p/keys [processing-sequence]
    :or      {max-resolver-weight 3600000}
    :as      env}]
  (if-let [[plan out] (reader-compute-plan env #{})]
    (let [key (-> env :ast :key)]
      (loop [[step & tail] plan
             failed-resolvers {}
             out-left         out]
        (if step
          (let [[key' resolver-sym] step
                {::keys [cache? batch? input] :or {cache? true} :as resolver}
                (get-in indexes [::index-resolvers resolver-sym])
                output     (resolver->output env resolver-sym)
                env        (assoc env ::resolver-data resolver)
                e          (select-keys (p/entity env) input)
                p          (p/params env)
                trace-data {:key         key
                            ::sym        resolver-sym
                            ::input-data e}
                response   (if cache?
                             (p.async/throw-err
                               (p/cached env [resolver-sym e p]
                                 (if (and batch? processing-sequence)
                                   (pt/tracing env (assoc trace-data ::pt/event ::call-resolver-batch)
                                     (let [_              (pt/trace env (assoc trace-data ::pt/event ::call-resolver-with-cache))
                                           items          (->> processing-sequence
                                                               (mapv #(entity-select-keys env % input))
                                                               (filterv #(all-values-valid? % input))
                                                               (distinct))
                                           _              (pt/trace env {::pt/event ::batch-items-ready
                                                                         ::items    items})
                                           batch-result   (call-resolver env items)
                                           _              (pt/trace env {::pt/event    ::batch-result-ready
                                                                         ::items-count (count batch-result)})
                                           linked-results (zipmap items batch-result)]
                                       (cache-batch env resolver-sym linked-results)
                                       (get linked-results e)))
                                   (call-resolver env e))))
                             (call-resolver env e))
                response   (or response {})
                replan     (fn [error]
                             (let [failed-resolvers (assoc failed-resolvers resolver-sym error)]
                               (update-resolver-weight env resolver-sym #(min (* (or % 1) 2) max-resolver-weight))
                               (if-let [[plan out'] (reader-compute-plan env failed-resolvers)]
                                 [plan failed-resolvers out'])))]

            (cond
              (map? response)
              (let [env'     (get response ::env env)
                    response (dissoc response ::env)]
                (p/swap-entity! env' #(merge response %))
                (if (and (contains? response key')
                         (not (p/break-values (get response key'))))
                  (let [out-provides (output->provides output)]
                    (pt/trace env' {::pt/event ::merge-resolver-response
                                    :key       key
                                    ::sym      resolver-sym})
                    (if (seq tail)
                      (recur tail failed-resolvers (set/difference out-left out-provides))
                      (process-simple-reader-response env' response)))

                  (if-let [[plan failed-resolvers out'] (replan (ex-info "Insufficient resolver output" {::pp/response-value response :key key'}))]
                    (recur plan failed-resolvers out')
                    (do
                      (if (seq tail)
                        (throw (ex-info "Insufficient resolver output" {::pp/response-value response :key key'})))

                      (process-simple-reader-response env' response)))))

              :else
              (if-let [[plan failed-resolvers out'] (replan (ex-info "Invalid resolve response" {::pp/response-value response}))]
                (recur plan failed-resolvers out')
                (do
                  (pt/trace env {::pt/event          ::invalid-resolve-response
                                 :key                key
                                 ::sym               resolver-sym
                                 ::pp/response-value response})
                  (throw (ex-info "Invalid resolve response" {::pp/response-value response})))))))))
    ::p/continue))

(defn- map-async-serial [f s]
  (go-catch
    (loop [out  []
           rest s]
      (if-let [item (first rest)]
        (recur
          (conj out (<?maybe (f item)))
          (next rest))
        out))))

(defn async-reader
  "DEPRECATED: use async-reader2

  Like reader, but supports async values on resolver return."
  [{::keys   [indexes] :as env
    ::p/keys [processing-sequence]}]
  (let [k (-> env :ast :key)
        p (p/params env)]
    (if (get-in indexes [::index-oir k])
      (go-catch
        (if-let [{:keys [e s]} (<? (async-pick-resolver env))]
          (let [{::keys [cache? batch? input] :or {cache? true} :as resolver}
                (resolver-data env s)
                env      (assoc env ::resolver-data resolver)
                response (if cache?
                           (<?maybe
                             (p/cached-async env [s e p]
                               (fn []
                                 (go-catch
                                   (if (and batch? processing-sequence)
                                     (let [items          (->> (<? (map-async-serial #(entity-select-keys env % input) processing-sequence))
                                                               (filterv #(all-values-valid? % input)))
                                           batch-result   (<?maybe (call-resolver env items))
                                           linked-results (zipmap items batch-result)]
                                       (cache-batch env s linked-results)
                                       (get linked-results e))
                                     (<?maybe (call-resolver env e)))))))
                           (<?maybe (call-resolver env e)))
                env'     (get response ::env env)
                response (dissoc response ::env)]
            (if-not (or (nil? response) (map? response))
              (throw (ex-info "Response from reader must be a map." {:sym s :response response})))
            (p/swap-entity! env' #(merge response %))
            (let [x (get response k)]
              (cond
                (sequential? x)
                (->> (mapv atom x) (p/join-seq env') <?maybe)

                (nil? x)
                (if (contains? response k)
                  x
                  ::p/continue)

                :else
                (-> (p/join (atom x) env') <?maybe))))
          ::p/continue))
      ::p/continue)))

(defn- async-read-cache-read
  [env resolver-sym e batch? processing-sequence trace-data input]
  (let [params (p/params env)]
    (p/cached-async env [resolver-sym e params]
      (fn []
        (go-catch
          (if (and batch? processing-sequence)
            (pt/tracing env (assoc trace-data ::pt/event ::call-resolver-batch)
              (let [_              (pt/trace env (assoc trace-data ::pt/event ::call-resolver-with-cache))
                    items          (->> processing-sequence
                                        (map-async-serial #(entity-select-keys env % input)) <?
                                        (filterv #(all-values-valid? % input))
                                        (distinct))
                    _              (pt/trace env {::pt/event ::batch-items-ready
                                                  ::items    items})
                    batch-result   (<?maybe (call-resolver env items))
                    _              (pt/trace env {::pt/event    ::batch-result-ready
                                                  ::items-count (count batch-result)})
                    linked-results (zipmap items batch-result)]
                (cache-batch env resolver-sym linked-results)
                (get linked-results e)))
            (<?maybe (call-resolver env e))))))))

(defn async-reader2
  "Like reader2, but supports async values on resolver return."
  [{::keys   [indexes max-resolver-weight]
    ::p/keys [processing-sequence]
    :or      {max-resolver-weight 3600000}
    :as      env}]
  (if-let [[plan out] (reader-compute-plan env #{})]
    (go-catch
     (let [key (-> env :ast :key)]
       (loop [[step & tail] plan
               failed-resolvers {}
               out-left         out]
          (if step
            (let [[key' resolver-sym] step
                  {::keys [cache? batch? input] :or {cache? true} :as resolver}
                  (get-in indexes [::index-resolvers resolver-sym])
                  output     (resolver->output env resolver-sym)
                  env        (assoc env ::resolver-data resolver)
                  e          (select-keys (p/entity env) input)
                  trace-data {:key         key
                              ::sym        resolver-sym
                              ::input-data e}
                  response   (if cache?
                               (<?maybe (async-read-cache-read env resolver-sym e batch? processing-sequence trace-data input))
                               (<?maybe (call-resolver env e)))
                  response   (or response {})
                  replan     (fn [error]
                               (let [failed-resolvers (assoc failed-resolvers resolver-sym error)]
                                 (update-resolver-weight env resolver-sym #(min (* (or % 1) 2) max-resolver-weight))
                                 (if-let [[plan out'] (reader-compute-plan env failed-resolvers)]
                                   [plan failed-resolvers out'])))]

              (cond
                (map? response)
                (let [env'     (get response ::env env)
                      response (dissoc response ::env)]
                  (p/swap-entity! env' #(merge response %))
                  (if (and (contains? response key')
                           (not (p/break-values (get response key'))))
                    (let [out-provides (output->provides output)]
                      (pt/trace env' {::pt/event ::merge-resolver-response
                                      :key       key
                                      ::sym      resolver-sym})
                      (if (seq tail)
                        (recur tail failed-resolvers (set/difference out-left out-provides))
                        (<?maybe (process-simple-reader-response env' response))))

                    (if-let [[plan failed-resolvers out'] (replan (ex-info "Insufficient resolver output" {::pp/response-value response :key key'}))]
                      (recur plan failed-resolvers out')
                      (do
                        (if (seq tail)
                          (throw (ex-info "Insufficient resolver output" {::pp/response-value response :key key'})))

                        (<?maybe (process-simple-reader-response env' response))))))

                :else
                (if-let [[plan failed-resolvers out'] (replan (ex-info "Invalid resolve response" {::pp/response-value response}))]
                  (recur plan failed-resolvers out')
                  (do
                    (pt/trace env {::pt/event          ::invalid-resolve-response
                                   :key                key
                                   ::sym               resolver-sym
                                   ::pp/response-value response})
                    (throw (ex-info "Invalid resolve response" {::pp/response-value response}))))))))))
    ::p/continue))

(defn parallel-batch-error [{::p/keys [processing-sequence] :as env} e]
  (let [{::keys [output]} (-> env ::resolver-data)
        item-count (count processing-sequence)]
    (pt/trace env {::pt/event ::batch-result-error
                   ::p/error  (p/process-error env e)})
    (let [output'   (output->provides output)
          base-path (->> env ::p/path (into [] (take-while keyword?)))]
      (doseq [o output'
              i (range item-count)]
        (p/add-error (assoc env ::p/path (conj base-path i o)) e))
      (repeat item-count (zipmap output' (repeat ::p/reader-error))))))

(defn group-input-indexes [inputs]
  (reduce
    (fn [acc [i input]]
      (update acc input (fnil conj #{}) i))
    {}
    inputs))

(defn parallel-batch [{::p/keys [processing-sequence path entity-path-cache]
                       :as      env}]
  (go-catch
    (let [{::keys       [input]
           resolver-sym ::sym} (-> env ::resolver-data)
          e          (select-keys (p/entity env) input)
          key        (-> env :ast :key)
          params     (p/params env)
          trace-data {:key         key
                      ::sym        resolver-sym
                      ::input-data e}]
      (pt/tracing env (assoc trace-data ::pt/event ::call-resolver-batch)
        (if (p/cache-contains? env [resolver-sym e params])
          (<! (p/cache-read env [resolver-sym e params]))
          (let [items-map      (->> (<? (map-async-serial #(entity-select-keys env % input) processing-sequence))
                                    (into [] (comp
                                               (map-indexed vector)
                                               (filter #(all-values-valid? (second %) input))
                                               (remove #(p/cache-contains? env [resolver-sym (second %) params]))))
                                    (group-input-indexes))
                items          (keys items-map)
                _              (pt/trace env {::pt/event ::batch-items-ready
                                              ::items    items})
                channels       (into [] (map (fn [resolver-input]
                                               (let [ch (async/promise-chan)]
                                                 (p/cache-hit env [resolver-sym resolver-input params] ch)
                                                 ch))) items)

                batch-result   (try
                                 (p.async/throw-err (<?maybe (call-resolver env items)))
                                 (catch #?(:clj Throwable :cljs :default) e
                                   (parallel-batch-error env e)))

                _              (pt/trace env {::pt/event    ::batch-result-ready
                                              ::items-count (count batch-result)})

                linked-results (zipmap items (mapv vector channels batch-result))]

            (if (and (not= ::p/reader-error (first batch-result))
                     (>= (count path) 2))
              (swap! entity-path-cache
                (fn entity-path-swap [cache]
                  (let [path (subvec path 0 (- (count path) 2))]
                    (reduce
                      (fn entity-path-outer-reduce [cache [item result]]
                        (reduce
                          (fn entity-path-inner-reduce [cache index]
                            (update cache (conj path index) #(merge result %)))
                          cache
                          (get items-map item)))
                      cache
                      (zipmap items batch-result))))))

            (doseq [[_ [ch value]] linked-results]
              (if value
                (async/put! ch (or value {}))
                (async/close! ch)))

            (second (get linked-results e [nil {}]))))))))

(defn parallel-reader
  [{::keys    [indexes max-resolver-weight]
    ::p/keys  [processing-sequence]
    ::pp/keys [waiting]
    :or       {max-resolver-weight 3600000}
    :as       env}]
  (if-let [[plan out] (reader-compute-plan env #{})]
    {::pp/provides
     out

     ::pp/response-stream
     (let [ch     (async/chan 10)
           key    (-> env :ast :key)
           params (p/params env)
           env    (assoc env ::plan-path plan)]
       (go
         (loop [[step & tail] plan
                failed-resolvers {}
                out-left         out
                waiting          waiting]
           (if step
             (let [[key' resolver-sym] step
                   {::keys [cache? batch? input] :or {cache? true} :as resolver}
                   (get-in indexes [::index-resolvers resolver-sym])
                   output     (resolver->output env resolver-sym)
                   env        (assoc env ::resolver-data resolver)
                   e          (select-keys (p/entity env) input)
                   trace-data {:key         key
                               ::sym        resolver-sym
                               ::input-data e}
                   response   (cond
                                (contains? waiting key')
                                (do
                                  (pt/trace env (assoc trace-data ::pt/event ::waiting-resolver ::waiting-key key'))
                                  (let [{::pp/keys [error]} (<! (pp/watch-pending-key env key'))]
                                    (or error ::watch-ready)))

                                cache?
                                (if (and batch? processing-sequence)
                                  (<! (parallel-batch env))
                                  (do
                                    (pt/trace env (assoc trace-data ::pt/event ::call-resolver-with-cache))
                                    (<!
                                      (p/cached-async env [resolver-sym e params]
                                        #(go-catch (or (<!maybe (call-resolver env e)) {}))))))

                                :else
                                (try
                                  (or (<?maybe (call-resolver env e)) {})
                                  (catch #?(:clj Throwable :cljs :default) e e)))
                   replan     (fn [value error]
                                (go
                                  (let [failed-resolvers (assoc failed-resolvers resolver-sym error)]
                                    (update-resolver-weight env resolver-sym #(min (* (or % 1) 2) max-resolver-weight))
                                    (if-let [[plan out'] (reader-compute-plan env failed-resolvers)]
                                      (do
                                        (>! ch {::pp/provides       out
                                                ::pp/waiting        out'
                                                ::pp/response-value value})
                                        [plan failed-resolvers out'])))))]

               (cond
                 (identical? ::pp/watch-pending-timeout response)
                 (recur plan failed-resolvers out-left (disj waiting key'))

                 (identical? ::watch-ready response)
                 (recur tail failed-resolvers (set/difference out-left (set (keys (p/entity env)))) waiting)

                 (map? response)
                 (let [response (dissoc response ::env)]
                   (p/swap-entity! env #(merge response %))
                   (if (and (contains? response key')
                            (not (p/break-values (get response key'))))
                     (let [out-provides (output->provides output)]
                       (pt/trace env {::pt/event ::merge-resolver-response
                                      :key       key
                                      ::sym      resolver-sym})
                       (>! ch {::pp/provides       out-provides
                               ::pp/response-value response})
                       (recur tail failed-resolvers (set/difference out-left out-provides) waiting))

                     (if-let [[plan failed-resolvers out'] (<! (replan response (ex-info "Insufficient resolver output" {::pp/response-value response :key key'})))]
                       (recur plan failed-resolvers out' waiting)
                       (let [err (ex-info "Insufficient resolver output" {::pp/response-value response :key key'})]
                         (p/swap-entity! env #(merge response %))
                         (if (seq tail)
                           (p/add-error env err))
                         (>! ch {::pp/provides       out
                                 ::pp/error          err
                                 ::pp/response-value (cond-> response
                                                       (not (contains? response key'))
                                                       (assoc key' ::p/not-found)

                                                       (seq tail)
                                                       (assoc key' ::p/reader-error))})
                         (async/close! ch)))))

                 (p.async/error? response)
                 (if-let [[plan failed-resolvers out'] (<! (replan {} response))]
                   (recur plan failed-resolvers out' waiting)
                   (do
                     (pt/trace env {::pt/event ::resolver-error
                                    :key       key
                                    ::sym      resolver-sym})
                     (p/add-error env response)
                     (>! ch {::pp/provides       out
                             ::pp/error          response
                             ::pp/response-value (if (seq tail)
                                                   {key ::p/reader-error}
                                                   (zipmap out-left (repeat ::p/reader-error)))})

                     (async/close! ch)))

                 :else
                 (if-let [[plan failed-resolvers out'] (<! (replan {} (ex-info "Invalid resolve response" {::pp/response-value response})))]
                   (recur plan failed-resolvers out' waiting)
                   (let [err (ex-info "Invalid resolve response" {::pp/response-value response})]
                     (pt/trace env {::pt/event          ::invalid-resolve-response
                                    :key                key
                                    ::sym               resolver-sym
                                    ::pp/response-value response})
                     (p/add-error env err)
                     (>! ch {::pp/provides       out
                             ::pp/error          err
                             ::pp/response-value {key ::p/reader-error}})
                     (async/close! ch)))))
             (async/close! ch))))
       ch)}
    ::p/continue))

(def index-reader
  {::indexes
   (fn [{::keys [indexes] :as env}]
     (p/join indexes env))})

(defn indexed-ident [{::keys [indexes] :as env}]
  (if-let [attr (p/ident-key env)]
    (if (contains? (::idents indexes) attr)
      {attr (p/ident-value env)})))

(defn resolver
  "Helper to return a resolver map"
  [sym {::keys [transform] :as options} resolve]
  (assert (symbol? sym) "Resolver name must be a symbol")
  (cond-> (merge {::sym sym ::resolve resolve} options)
    transform transform))

(defmacro defresolver [sym arglist config & body]
  (let [fqsym (if (namespace sym)
                sym
                (symbol (name (ns-name *ns*)) (name sym)))]
    `(def ~sym
       (resolver '~fqsym
         ~config
         (fn ~sym ~arglist ~@body)))))

(defn attr-alias-name [from to]
  (symbol (str (munge (subs (str from) 1)) "->" (munge (subs (str to) 1)))))

(defn alias-resolver
  "Create a resolver that will convert property `from` to a property `to` with
  the same value. This only creates the alias in one direction"
  [from to]
  {::sym     (attr-alias-name from to)
   ::input   #{from}
   ::output  [to]
   ::resolve (fn [_ input] {to (get input from)})})

(defn alias-resolver2
  "Like alias-resolver, but returns a vector containing the alias in both directions."
  [from to]
  [(alias-resolver from to)
   (alias-resolver to from)])

(defn mutation
  "Helper to return a mutation map"
  [sym {::keys [transform] :as options} mutate]
  (assert (symbol? sym) "Mutation name must be a symbol")
  (cond-> (merge {::sym sym ::mutate mutate} options)
    transform transform))

(defmacro defmutation [sym arglist config & body]
  (let [fqsym (symbol (name (ns-name *ns*)) (name sym))]
    `(def ~sym
       (mutation '~fqsym
         ~config
         (fn ~sym ~arglist ~@body)))))

(defn ident-reader
  "Reader for idents on connect, this reader will make a join to the ident making the
  context have that ident key and value. For example the ident [:user/id 123] will make
  a join to a context {:user/id 123}. This reader will continue if connect doesn't have
  a path to respond to that ident.

  This reader also supports params to add more context besides the entity value. To use
  that send the `:pathom/context` param with the join, as in:

  [{([:user/id 123] {:pathom/context {:user/foo \"bar\"}})
    [:user/name]}]

  In the previous case, the context will be the merge between the identity and the
  context, {:user/id 123 :user/foo \"bar\"} in this case."
  [env]
  (if-let [ent (indexed-ident env)]
    (let [extra-context (get-in env [:ast :params :pathom/context])
          ent           (merge ent extra-context)]
      (p/join (atom ent) env))
    ::p/continue))

(defn open-ident-reader
  "Like ident-reader, but ident key doesn't have to be in the index, this will respond
  to any ident join. Also supports extra context with :pathom/context param."
  [env]
  (if-let [key (p/ident-key env)]
    (let [extra-context (get-in env [:ast :params :pathom/context])
          ent           (merge {key (p/ident-value env)} extra-context)]
      (p/join (atom ent) env))
    ::p/continue))

(defn batch-resolver
  "Return a resolver that will dispatch to single-fn when the input is a single value, and multi-fn when
  multiple inputs are provided (on batch cases).

  Many times the implementation for the single can be the same as the multi, getting the first item, and
  if you provide only one function (the multi-fn) we will setup the single one automatically running
  the multi and returning the first result."
  ([multi-fn]
   (batch-resolver
     (fn [env input]
       (let-chan [res (multi-fn env [input])]
         (first res)))
     multi-fn))
  ([single-fn multi-fn]
   (fn [env input]
     (if (sequential? input)
       (multi-fn env input)
       (single-fn env input)))))

(defn transform-batch-resolver
  "Given a resolver that implements the many case, return one that also supports the
  single case by running the many and taking the first result out."
  [resolver]
  (-> resolver (assoc ::batch? true)
      (update ::resolve batch-resolver)))

(defn transform-auto-batch
  "Given a resolver that implements the single item case, wrap it implementing a batch
  resolver that will make a batch by running many in parallel, using `n` as the concurrency
  number."
  [n]
  (fn [{::keys [resolve] :as resolver}]
    (assoc resolver
      ::batch? true

      ::resolve
      (batch-resolver
        (fn [env inputs]
          (go
            (let [from-chan (async/chan n)
                  out-chan  (async/chan n)]
              (async/onto-chan from-chan inputs)
              (async/pipeline-async n
                out-chan
                (fn auth-batch-pipeline [input res-ch]
                  (go
                    (let [res (<!maybe (resolve env input))]
                      (async/>! res-ch res)
                      (async/close! res-ch))))
                from-chan)
              (<! (async/into [] out-chan)))))))))

(def all-readers [reader ident-reader index-reader])
(def all-async-readers [async-reader ident-reader index-reader])
(def all-parallel-readers [parallel-reader ident-reader index-reader])

(defn mutation-dispatch
  "Helper method that extract key from ast symbol from env. It's recommended to use as a dispatch method for creating
  multi-methods for mutation dispatch."
  [env _]
  (get-in env [:ast :key]))

(defn mutation-dispatch-embedded
  "This dispatch method will fire the mutation by looking at the ::pc/mutate
  key in the mutation map details."
  [{::keys [indexes] :as env} entity]
  (let [sym (get-in env [:ast :key])
        {::keys [mutate]} (get-in indexes [::index-mutations sym])]
    (assert mutate (str "Can't find mutate fn for " sym))
    (mutate env entity)))

(defn mutate
  "Sync mutate function to integrate connect mutations to pathom parser."
  [{::keys [indexes mutate-dispatch mutation-join-globals]
    :keys  [query]
    :or    {mutation-join-globals []}
    :as    env} sym' {:keys [pathom/context] :as input}]
  (if-let [{::keys [sym]} (get-in indexes [::index-mutations sym'])]
    (let [env (assoc-in env [:ast :key] sym)]
      {:action #(let [res (mutate-dispatch (assoc env ::source-mutation sym') input)
                      res (cond-> res (and context (map? res)) (merge context))]
                  (if (and query (map? res))
                    (merge (select-keys res mutation-join-globals)
                           (p/join (atom res) env))
                    res))})
    (throw (ex-info "Mutation not found" {:mutation sym'}))))

(defn mutate-async
  "Async mutate function to integrate connect mutations to pathom parser."
  [{::keys [indexes mutate-dispatch mutation-join-globals]
    :keys  [query]
    :or    {mutation-join-globals []}
    :as    env} sym' {:keys [pathom/context] :as input}]
  (if-let [{::keys [sym]} (get-in indexes [::index-mutations sym'])]
    (let [env (assoc-in env [:ast :key] sym)]
      {:action #(go-catch
                  (let [res (<?maybe (mutate-dispatch (assoc env ::source-mutation sym') input))
                        res (cond-> res (and context (map? res)) (merge context))]
                    (if query
                      (merge (select-keys res mutation-join-globals)
                             (<? (p/join (atom res) env)))
                      res)))})
    (throw (ex-info "Mutation not found" {:mutation sym'}))))

;;;;;;;;;;;;;;;;;;;

(defn resolver-factory
  "Given multi-method mm and index atom idx, returns a function with the given signature:
   [sym config f], the function will be add to the mm and will be indexed using config as
   the config params for connect/add."
  [mm idx]
  (fn resolver-factory-internal
    [sym config f]
    (assert (symbol? sym) "Resolver name must be a symbol")
    (defmethod mm sym [env input] (f env input))
    (swap! idx add sym (merge {::resolve f} config))))

(defn mutation-factory
  [mm idx]
  (fn mutation-factory-internal
    [sym config f]
    (assert (symbol? sym) "Mutation name must be a symbol")
    (defmethod mm sym [env input] (f env input))
    (swap! idx add-mutation sym (merge {::mutate f} config))))

(defn- cached [cache x f]
  (if cache
    (if (contains? @cache x)
      (get @cache x)
      (let [res (f)]
        (swap! cache assoc x res)
        res))
    (f)))

(defn discover-attrs [{::keys [index-io cache] :as index} ctx]
  (cached cache ctx
    (fn []
      (let [base-keys
            (if (> (count ctx) 1)
              (let [tree (->> ctx
                              (repeat (dec (count ctx)))
                              (map-indexed #(drop (- (count %2) (inc %)) %2))
                              (reduce (fn [a b]
                                        (let [attrs (discover-attrs index (vec b))]
                                          (if (nil? a)
                                            attrs
                                            (update-in a (reverse (drop-last b)) merge-io attrs))))
                                nil))]
                (get-in tree (->> ctx reverse next vec)))
              (merge-io (get-in index-io [#{} (first ctx)])
                (get index-io #{(first ctx)} {})))]
        (loop [available index-io
               collected base-keys]
          (let [attrs   (->> collected keys set)
                matches (remove (fn [[k _]] (seq (set/difference k attrs))) available)]
            (if (seq matches)
              (recur
                (reduce #(dissoc % %2) available (keys matches))
                (reduce merge-io collected (vals matches)))
              collected)))))))

(defn reprocess-index
  "This will use the ::index-resolvers to re-build the index. You might need that if in development you changed some definitions
  and got in a dirty state somehow"
  [{::keys [index-resolvers]}]
  (reduce-kv add {} index-resolvers))

(defn data->shape
  "Helper function to transform a data into an output shape."
  [data]
  (if (map? data)
    (->> (reduce-kv
           (fn [out k v]
             (conj out
               (cond
                 (map? v)
                 {k (data->shape v)}

                 (sequential? v)
                 (let [shape (reduce
                               (fn [q x]
                                 (p/merge-queries q (data->shape x)))
                               []
                               v)]
                   (if (seq shape)
                     {k shape}
                     k))

                 :else
                 k)))
           []
           data)
         (sort-by (comp pr-str #(if (map? %) (ffirst %) %)))
         vec)))

(defn batch-restore-sort
  "Sorts output list to match input list.

  When doing batch requests you must return a vector in the same order respective to
  the order of inputs. Many times when calling an external API sending a list of ids
  the returned list doesn't always garantee input order. To fix these cases this
  function can restore the order. Example:

      (fn batch-resolver [env inputs]
        ; inputs => [{:my.entity/id 1} {:my.entity/id 2}]
        (batch-restore-sort {::inputs inputs
                             ::key    :my.entity/id}
          [{:my.entity/id    2
            :my.entity/color :my.entity.color/green}
           {:my.entity/id    1
            :my.entity/color :my.entity.color/purple}])
        ; => [{:my.entity/id    1
        ;      :my.entity/color :my.entity.color/purple}
        ;     {:my.entity/id    2
        ;      :my.entity/color :my.entity.color/green}]

  You can provide a ::batch-default function to fill in for missing items on the output. The
  default function will take the respective input and must return a map containing
  any data you want to add, usually some nil keys to declare that value should not
  require further lookup."
  [{::keys [inputs key batch-default]} items]
  (let [index         (group-by key items)
        batch-default (or batch-default #(hash-map key (get % key)))]
    (into [] (map (fn [input]
                    (or (first (get index (get input key)))
                        (batch-default input)))) inputs)))

;; resolvers

(def index-explorer-resolver
  (resolver `index-explorer-resolver
    {::output [:com.wsscode.pathom.viz.index-explorer/index]}
    (fn [env _] {:com.wsscode.pathom.viz.index-explorer/index (get env ::indexes)})))

(def indexes-resolver
  (resolver `indexes-resolver
    {::output [{::indexes
                [::index-io ::index-oir ::idents ::autocomplete-ignore
                 {::index-attributes
                  [::attribute
                   ::attr-leaf-in
                   ::attr-branch-in
                   ::attr-combinations
                   ::attr-input-in
                   ::attr-output-in
                   ::attr-mutation-output-in
                   ::attr-mutation-param-in
                   ::attr-provides
                   ::attr-reach-via]}
                 {::index-resolvers
                  [::sym ::input ::output ::params]}
                 {::index-mutations
                  [::sym ::output ::params]}]}]}
    (fn [env _] (select-keys env [::indexes]))))

(def resolver-weights-resolver
  (resolver `resolver-weights-resolver
    {::output [::resolver-weights]}
    (fn [env _]
      {::resolver-weights (some-> env ::resolver-weights deref)})))

(def resolver-weights-sorted-resolver
  (resolver `resolver-weights-sorted-resolver
    {::output [::resolver-weights-sorted]}
    (fn [env _]
      {::resolver-weights-sorted
       (some->> env ::resolver-weights deref (sort-by second #(compare %2 %)))})))

(def resolver-weights-resolvers [resolver-weights-resolver resolver-weights-sorted-resolver])

(def connect-resolvers [indexes-resolver resolver-weights-resolvers])

;; plugins

(defn connect-plugin
  "This plugin facilitates the connect setup in a parser. It works by wrapping the parser,
  it setups the connect resolver and mutation dispatch using the embedded dispatchers (check resolver
  map format in the book for more details). It also sets up the resolver weights for load
  balacing calculation. Here are the available options to configure the plugin:

  `::pc/indexes` - provide an index atom to be used, otherwise the plugin will create one
  `::pc/register` - a resolver, mutation or sequence of resolvers/mutations to register in
  the index
  `::pc/pool-chan` - override the thread pool, use `nil` to disable thread pool feature (not recommneded)

  This plugin also looks for the key `::pc/register` in the other plugins used in the
  parser configuration, this enable plugins to provide resolvers/mutations to be available
  in your connect system.

  By default this plugin will also register resolvers to provide the index itself, if
  you for some reason need to hide it you can dissoc the `::pc/register` from the output
  and they will not be available, but consider that doing so you lose the ability to
  have instrospection in tools like Pathom Viz and Fulcro Inspect."
  ([] (connect-plugin {}))
  ([{::keys [indexes] :as env}]
   (let [indexes   (or indexes (atom {}))
         pool-chan (get env ::pool-chan)]
     {::p/wrap-parser2
      (fn connect-wrap-parser [parser {::p/keys [plugins]}]
        (let [plugin-registry  (keep ::register plugins)
              resolver-weights (atom {})]
          (swap! indexes register [plugin-registry (get env ::register [])])
          (fn [env tx]
            (parser
              (merge
                {::resolver-dispatch resolver-dispatch-embedded
                 ::mutate-dispatch   mutation-dispatch-embedded
                 ::indexes           @indexes
                 ::resolver-weights  resolver-weights
                 ::pool-chan         pool-chan}
                env) tx))))

      ::indexes
      indexes

      ::register
      connect-resolvers})))

(when p.misc/INCLUDE_SPECS
  (s/fdef add
    :args (s/cat :indexes (s/or :index ::indexes :blank #{{}})
                 :sym ::sym
                 :sym-data (s/? (s/keys :opt [::input ::output])))
    :ret ::indexes)

  (s/fdef add-mutation
    :args (s/cat :indexes (s/or :index ::indexes :blank #{{}})
                 :sym ::sym
                 :sym-data (s/? (s/keys :opt [::params ::output])))
    :ret ::indexes)

  (s/fdef register
    :args (s/cat
            :indexes ::indexes
            :register ::register))

  (s/fdef pick-resolver
    :args (s/cat :env (s/keys :req [::indexes] :opt [::dependency-track])))

  (s/fdef path-cost
    :args (s/cat :env ::p/env :plan (s/coll-of ::sym)))

  (s/fdef project-parent-query-attributes
    :args (s/cat :env ::p/env)
    :ret ::attributes-set)

  (s/fdef defresolver
    :args (s/cat
            :sym simple-symbol?
            :arglist (s/coll-of any? :kind vector? :count 2)
            :config any?
            :body (s/* any?)))

  (s/fdef alias-resolver
    :args (s/cat :from ::eql/property :to ::eql/property)
    :ret ::resolver)

  (s/fdef alias-resolver2
    :args (s/cat :from ::eql/property :to ::eql/property)
    :ret (s/tuple ::resolver ::resolver))

  (s/fdef defmutation
    :args (s/cat
            :sym simple-symbol?
            :arglist (s/coll-of any? :kind vector? :count 2)
            :config any?
            :body (s/* any?)))

  (s/fdef discover-attrs
    :args (s/cat :indexes ::indexes :ctx (s/coll-of ::attribute))
    :ret ::io-map))
