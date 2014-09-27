(ns frontend.components.app
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.components.account :as account]
            [frontend.components.about :as about]
            [frontend.components.admin :as admin]
            [frontend.components.aside :as aside]
            [frontend.components.build :as build-com]
            [frontend.components.dashboard :as dashboard]
            [frontend.components.documentation :as docs]
            [frontend.components.add-projects :as add-projects]
            [frontend.components.changelog :as changelog]
            [frontend.components.enterprise :as enterprise]
            [frontend.components.errors :as errors]
            [frontend.components.footer :as footer]
            [frontend.components.header :as header]
            [frontend.components.inspector :as inspector]
            [frontend.components.integrations :as integrations]
            [frontend.components.jobs :as jobs]
            [frontend.components.key-queue :as keyq]
            [frontend.components.placeholder :as placeholder]
            [frontend.components.pricing :as pricing]
            [frontend.components.privacy :as privacy]
            [frontend.components.project-settings :as project-settings]
            [frontend.components.security :as security]
            [frontend.components.shared :as shared]
            [frontend.components.stories :as stories]
            [frontend.components.language-landing :as language-landing]
            [frontend.components.landing :as landing]
            [frontend.components.org-settings :as org-settings]
            [frontend.components.common :as common]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.seq :refer [dissoc-in]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ankha.core :as ankha])
  (:require-macros [frontend.utils :refer [html]]))

(def keymap
  (atom nil))

(defn loading [app owner]
  (reify
    om/IRender
    (render [_] (html [:div.loading-spinner common/spinner]))))

(defn dominant-component [app-state]
  (condp = (get-in app-state [:navigation-point])
    :build build-com/build
    :dashboard dashboard/dashboard
    :add-projects add-projects/add-projects
    :project-settings project-settings/project-settings
    :org-settings org-settings/org-settings
    :account account/account

    :admin admin/admin
    :build-state admin/build-state

    :loading loading

    :landing landing/home
    :about about/about
    :pricing pricing/pricing
    :jobs jobs/jobs
    :privacy privacy/privacy
    :security security/security
    :security-hall-of-fame security/hall-of-fame
    :enterprise enterprise/enterprise
    :shopify-story stories/shopify
    :language-landing language-landing/language-landing
    :docker-integration integrations/docker
    :changelog changelog/changelog
    :documentation docs/documentation

    :error errors/error-page))

(defn app* [app owner]
  (reify
    om/IDisplayName (display-name [_] "App")
    om/IRender
    (render [_]
      (if-not (:navigation-point app)
        (html [:div#app])

        (let [controls-ch (om/get-shared owner [:comms :controls])
              persist-state! #(put! controls-ch [:state-persisted])
              restore-state! #(put! controls-ch [:state-restored])
              dom-com (dominant-component app)
              show-inspector? (get-in app state/show-inspector-path)
              logged-in? (get-in app state/user-path)
              ;; simple optimzation for real-time updates when the build is running
              app-without-container-data (dissoc-in app state/container-data-path)
              slim-aside? (get-in app state/slim-aside-path)]
          (reset! keymap {["ctrl+s"] persist-state!
                          ["ctrl+r"] restore-state!})
          (html
           (let [inner? (get-in app state/inner?-path)]

             [:div#app {:class (concat [(if inner? "inner" "outer")]
                                       (when slim-aside? ["aside-slim"]))}
              (om/build keyq/KeyboardHandler app-without-container-data
                        {:opts {:keymap keymap
                                :error-ch (get-in app [:comms :errors])}})
              (when show-inspector?
                ;; TODO inspector still needs lots of work. It's slow and it defaults to
                ;;     expanding all datastructures.
                (om/build inspector/inspector app))
              (when (and inner? logged-in?)
                (om/build aside/aside app-without-container-data))
              [:main.app-main
               (om/build header/header app-without-container-data)
               [:div.main-body
                (om/build dom-com app)]
               [:footer.main-foot
                (footer/footer)]
               (when-not logged-in?
                 (om/build shared/sticky-help-link app))]])))))))


(defn app [app owner]
  (reify om/IRender (render [_] (om/build app* (dissoc app :inputs)))))
