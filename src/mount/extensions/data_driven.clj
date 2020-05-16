(ns mount.extensions.data-driven
  "Extension to declare which states are started (or stopped) and how,
  using plain data."
  {:clojure.tools.namespace.repl/load   false
   :clojure.tools.namespace.repl/unload false}
  (:require [clojure.set :as set]
            [mount.extensions.basic :as basic]
            [mount.extensions.metadata :as metadata]
            [mount.lite :as mount]))

;;; Internals.

(defn- resolve* [symbol]
  (-> symbol resolve deref))

(defn- wrap-with-only [f states]
  (fn []
    (basic/with-only (map resolve* states)
      (f))))

(defn- wrap-with-except [f states]
  (fn []
    (basic/with-except (map resolve* states)
      (f))))

(defn- wrap-with-substitutes [f substitutes]
  (let [resolved (reduce-kv (fn [a k v] (assoc a (resolve* k) (resolve* v))) {} substitutes)]
    (fn []
      (mount/with-substitutes resolved
        (f)))))

(defn- wrap-with-system-map [f system-map]
  (let [resolved (reduce-kv (fn [a k v] (assoc a (resolve* k) v)) {} system-map)]
    (fn []
      (mount/with-system-map resolved
        (f)))))

(defn- wrap-with-system-key [f system-key]
  (fn []
    (mount/with-system-key system-key
      (f))))

(defn- wrap-with-metadata [f predicate]
  (let [resolved (resolve predicate)]
    (fn []
      (metadata/with-metadata resolved
        (f)))))

(defn- key->fn [key]
  (if-let [f (some-> (namespace key)
                     (doto (-> symbol require))
                     (symbol (name key))
                     resolve
                     deref)]
    (if (fn? f)
      f
      (throw (RuntimeException. (str "key " key " does not resolve to a function."))))
    (throw (RuntimeException. (str "cannot resolve key " key ".")))))

(def ^:private predefined-keys
  {:only        ::wrap-with-only
   :except      ::wrap-with-except
   :substitutes ::wrap-with-substitutes
   :system-map  ::wrap-with-system-map
   :system-key  ::wrap-with-system-key
   :metadata    ::wrap-with-metadata})


;;; Public API.

(defn with-config*
  "Same as the `with-config` macro, but now the body is in a 0-arity
  function."
  [config f]
  (assert (map? config) "config must be a map.")
  ((reduce-kv (fn [f k v]
                (let [wrapper (key->fn k)]
                  (wrapper f v)))
              f
              (set/rename-keys config predefined-keys))))

(defmacro with-config
  "Use a plain data map to declare various options regarding how
  mount-lite should behave inside the given body. To refer to
  defstates or other globally defined values, use qualified symbols.
  The data map supports the following keys:

   :only - wrap the body with the basic/with-only extension.

   :except - wrap the body with the basic/with-except extension.

   :substitutes - wrap the body with a map of substitutions.

   :system-map - wrap the body with a system map.

   :system-key - wrap the body with a system key.

   :metadata - wrap the body with the metadata/with-metadata extension.

  Next to the predefined keys above, one can supply your own namespace
  qualified keys. These keys must point to wrapper functions that
  receive a 0-arity function and the data value, returning a 0-arity
  function calling the given function."
  [config & body]
  `(with-config* ~config
     (fn [] ~@body)))
