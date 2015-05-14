(ns pc.diff
  (:require [clojure.data]
            [clojure.set :as set]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]
            [pc.datomic.web-peer :as web-peer]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer-model]
            [pc.render :as render])
  (:import java.util.UUID))

(defn render-diff [db doc tx-before tx-after]
  (let [db-before (d/as-of db tx-before)
        db-after (d/as-of db tx-after)
        before-ids (pc.utils/inspect (set (layer-model/find-ids-by-document db-before doc)))
        after-ids (pc.utils/inspect (set (layer-model/find-ids-by-document db-after doc)))
        [deleted-ids added-ids stable-ids] (clojure.data/diff before-ids after-ids)]
    (render/render-layers (concat (map #(assoc (into {} (d/entity db-after %))
                                               :layer/fill "green"
                                               :layer/stroke "green")
                                       added-ids)
                                  (map #(assoc (into {} (d/entity db-before %))
                                               :layer/fill "red"
                                               :layer/stroke "red")
                                       deleted-ids)
                                  (map #(into {} (d/entity db-after %))
                                       stable-ids)))))
