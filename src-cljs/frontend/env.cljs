(ns frontend.env)

(def ^:dynamic env-var nil)

(defn env []
  (or env-var (let [render-context (aget js/window "renderContext")]
                (if render-context
                  (-> render-context (aget "env") keyword)
                  :production))))

(defn production? []
  (= (env) :production))

(defn staging? []
  (= (env) :staging))

(defn test? []
  (= (env) :test))

(defn development? []
  (= (env) :development))
