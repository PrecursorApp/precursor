(ns frontend.components.inputs
  (:require [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]))

;; This lets us access state in a child component that we don't want to pass through the entire
;; nested component chain. It should only be used for state that shouldn't trigger a re-render,
;; like form inputs.
;;
;; Example usage:
;;
;; (defn my-component [app owner]
;;   (reify
;;     om/IRender
;;     (render [_]
;;       (let [inputs (inputs/get-inputs-from-app-state)
;;             my-value (:my-value inputs)]
;;         (html
;;          [:form
;;           [:input {:value my-value
;;                    :on-click #(put! controls-ch [:my-input-clicked {:my-value my-value}])}]])))))
;;
;; Inputs are cleared out on page transitions, you should also clear them out on successful submits, e.g.
;; and to your controls-handler: (put! controls-ch [:clear-inputs {:paths [[:my-value]]}])

(defn get-inputs-from-app-state
  "Helper function to get the inputs that we've replicated in the component owner"
  [owner]
  (:inputs @(om/get-shared owner [:_app-state-do-not-use])))
