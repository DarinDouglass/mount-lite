(ns mount.lite
  "The core namespace providing the public API"
  (:import [clojure.lang IDeref IRecord]
           [java.util Map]))

;;;; Internals.

;;; The state protocol.

(defprotocol ^:no-doc IState
  (start* [_])
  (stop* [_])
  (status* [_]))

;;; The state protocol implementation.

(defonce ^:private itl (InheritableThreadLocal.))

(defn- throw-started
  [name]
  (throw (ex-info (format "state %s already started %s" name
                          (if (.get itl) "in this session" ""))
                  {:name name})))

(defn- throw-unstarted
  [name]
  (throw (ex-info (format "state %s not started %s" name
                          (if (.get itl) "in this session" ""))
                  {:name name})))

(defn- throw-not-found
  [var]
  (throw (ex-info (format "var %s is not a state" var)
                  {:var var})))

(defrecord State [start-fn stop-fn name sessions]
  IState
  (start* [this]
    (if (= :stopped (status* this))
      (let [value (start-fn)]
        (swap! sessions assoc (.get itl) {::value value ::stop-fn stop-fn}))
      (throw-started name)))

  (stop* [this]
    (if (= :started (status* this))
      (let [stop-fn (get-in @sessions [(.get itl) ::stop-fn])]
        (stop-fn)
        (swap! sessions dissoc (.get itl)))
      (throw-unstarted name)))

  (status* [_]
    (if (get @sessions (.get itl))
      :started
      :stopped))

  IDeref
  (deref [this]
    (if (= :started (status* this))
      (get-in @sessions [(.get itl) ::value])
      (throw-unstarted name))))

(prefer-method print-method Map IDeref)
(prefer-method print-method IRecord IDeref)
(alter-meta! #'->State assoc :private true)
(alter-meta! #'map->State assoc :private true)

;;; Utility functions

(defn- name-with-attrs
  [name [arg1 arg2 & argx :as args]]
  (let [[attrs args] (cond (and (string? arg1) (map? arg2)) [(assoc arg2 :doc arg1) argx]
                           (string? arg1)                   [{:doc arg1} (cons arg2 argx)]
                           (map? arg1)                      [arg1 (cons arg2 argx)]
                           :otherwise                       [{} args])]
    [(with-meta name (merge (meta name) attrs)) args]))

(defn- var-status=
  [status]
  (fn [var]
    (= (-> var deref status*) status)))

(defn- find-index
  [elem coll]
  (first (keep-indexed (fn [i e]
                         (when (= e elem)
                           i))
                       coll)))

(defn- keyword->symbol
  [kw]
  (symbol (namespace kw) (name kw)))

(defn- resolve-keyword
  [kw]
  (resolve (keyword->symbol kw)))

(defn- prune-states
  [states]
  (->> states (filter resolve-keyword) (into (empty states))))


;;; Global state.

(defonce
  ^{:dynamic true
    :doc "Atom keeping track of defined states (by namespaced
    keywords) internally. Declared public and dynamic here, as an
    extension point to influence which states are started or stopped.
    Do not fiddle with the root binding."}
  *states* (atom []))

(defonce
  ^{:dynamic true
    :doc "Can be bound to a map with vars as keys and State records as
    values. The :start and :stop expressions of the State value will
    be used when the corresponding var key is started. The
    `with-substitutes` macro offers a nicer syntax."}
  *substitutes* nil)


;;;; Public API

(defmacro state
  "Create an anonymous state, useful for substituting. Supports three
  keyword arguments. A required :start expression, an optional :stop
  expression, and an optional :name for the state."
  [& {:keys [start stop name] :or {name "-anonymous-"}}]
  (if start
    `(#'map->State {:start-fn (fn [] ~start)
                    :stop-fn  (fn [] ~stop)
                    :name     ~name})
    (throw (ex-info "missing :start expression" {}))))

(defmacro defstate
  "Define a state. At least a :start expression should be supplied.
  Optionally one can define a :stop expression. Supports docstring and
  attribute map."
  [name & args]
  (let [[name args] (name-with-attrs name args)
        current     (resolve name)]
    `(do (defonce ~name (#'map->State {:sessions (atom nil)}))
         (let [local# (state :name ~(str name) ~@args)
               var#   (var ~name)
               kw#    (keyword (str (.ns var#)) (str (.sym var#)))]
           (alter-var-root var# merge (dissoc local# :sessions))
           (swap! *states* #(vec (distinct (conj % kw#))))
           var#))))

(defn start
  "Start all the loaded defstates, or only the defstates up to the
  given state var. Only stopped defstates are started. They are
  started in the context of the current session."
  ([]
   (start nil))
  ([up-to-var]
   (let [states (map resolve-keyword (swap! *states* prune-states))]
     (when-let [up-to (or up-to-var (last states))]
       (if-let [index (find-index up-to states)]
         (let [vars (->> states (take (inc index)) (filter (var-status= :stopped)))]
           (doseq [var vars]
             (let [substitute (-> (get *substitutes* var)
                                  (select-keys [:start-fn :stop-fn]))
                   state      (merge @var substitute)]
               (try
                 (start* state)
                 (catch Throwable t
                   (throw (ex-info (format "error while starting state %s" var)
                                   {:var var} t))))))
           vars)
         (throw-not-found up-to-var))))))

(defn stop
  "Stop all the loaded defstates, or only the defstates down to the
  given state var. Only started defstates are stopped. They are
  stopped in the context of the current session."
  ([]
   (stop nil))
  ([down-to-var]
   (let [states  (map resolve-keyword (swap! *states* prune-states))]
     (when-let [down-to (or down-to-var (first states))]
       (if-let [index (find-index down-to states)]
         (let [vars (->> states (drop index) (filter (var-status= :started)) (reverse))]
           (doseq [var vars]
             (try
               (stop* @var)
               (catch Throwable t
                 (throw (ex-info (format "error while stopping state %s" var)
                                 {:var var} t)))))
           vars)
         (throw-not-found down-to-var))))))

(defn status
  "Retrieve status map for all states."
  []
  (let [vars (map resolve-keyword (swap! *states* prune-states))]
    (reduce (fn [m v]
              (assoc m v (-> v deref status*)))
            {} vars)))

(defmacro with-substitutes
  "Given a vector with var-state pairs, an inner start function will
  use the :start expression of the substitutes for the specified
  vars. Nested `with-substitutes` are merged."
  [var-sub-pairs & body]
  `(let [merged# (merge *substitutes* (apply hash-map ~var-sub-pairs))]
     (binding [*substitutes* merged#]
       ~@body)))

(defmacro with-session
  "Creates a new thread, with a new system of states. All states are
  initially in the stopped status in this thread, regardless of the
  status in the thread that spawns this new session. This spawned
  thread and its subthreads will automatically use the states that are
  started within this thread or subthreads. Exiting the spawned thread
  will automatically stop all states in this session.

  Returns a map with the spawned :thread and a :promise that will be
  set to the result of the body or an exception."
  [& body]
  `(let [p# (promise)]
     {:thead  (doto (Thread. (fn []
                               (.set @#'itl (Thread/currentThread))
                               (try
                                 (deliver p# (do ~@body))
                                 (catch Throwable t#
                                   (deliver p# t#)
                                   (throw t#))
                                 (finally
                                   (stop)))))
                (.start))
      :result p#}))
