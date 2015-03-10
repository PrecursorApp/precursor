(ns frontend.components.team
  (:require [frontend.utils :as utils]
            [om.core :as om])
  (:require-macros [sablono.core :refer (html)]))

(defn team-settings [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:permission-grant-email ""})
    om/IRenderState
    (render-state [_ {:keys [permission-grant-email]}]
      (let [team (:team app)
            cast! (om/get-shared owner :cast!)
            submit-form (fn [e]
                          (cast! :team-permission-grant-submitted {:email permission-grant-email})
                          (om/set-state! owner :permission-grant-email "")
                          (utils/stop-event e))]
        (html
         [:div.menu-view
          [:article
           [:h2.make
            (:team/subdomain team)]
           [:p.make
            "Any docs you create in the " (:team/subdomain team)
            " subdomain will be private to your team by default."
            " Add your teammate's email to add them to your team."]
           [:form.menu-invite-form.make
            {:on-submit submit-form
             :on-key-down #(when (= "Enter" (.-key %))
                             (submit-form %))}
            [:input
             {:type "text"
              :required "true"
              :data-adaptive ""
              :value (or permission-grant-email "")
              :on-change #(om/set-state! owner :permission-grant-email (.. % -target -value))}]
            [:label
             {:data-placeholder "Teammate's email"
              :data-placeholder-nil "What's your teammate's email?"
              :data-placeholder-forgot "Don't forget to submit!"}]]]])))))
