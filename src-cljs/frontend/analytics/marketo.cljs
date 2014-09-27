(ns frontend.analytics.marketo
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.ajax :as ajax]
            [goog.net.Cookies]))

(defn munchkin?
  "Check if munchkin is defined. If not, then we probably won't be able to sumbit
   the form to them."
  []
  (boolean (aget js/window "mktoMunchkin")))

(defn munchkin-defaults []
  {:_mkto_trk (.get (goog.net.Cookies. js/window.document) "_mkto_trk")
   :munchkinId "894-NPA-635"})

(defn submit-munchkin-form
  "Submits the marketo form with a fallback to /about/contact"
  [form-id params]
  (if (munchkin?)
    (ajax/managed-form-post "http://app-abm.marketo.com/index.php/leadCapture/save2"
                            :params (merge (munchkin-defaults)
                                           {:formVid form-id
                                            :formid form-id}
                                           params))
    (ajax/managed-form-post "/about/contact"
                            :params {:email (:Email params)
                                     :message params})))
