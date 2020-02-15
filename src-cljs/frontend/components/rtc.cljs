(ns frontend.components.rtc
  (:require [frontend.utils :as utils]
            [om.core :as om]
            [om.dom :as dom]))

(defn audio [stream owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (when-let [audio (om/get-node owner "audio")]
        (aset audio "srcObject" stream)))
    om/IDidUpdate
    (did-update [_ _ _]
      (when-let [audio (om/get-node owner "audio")]
        (aset audio "srcObject" stream)))
    om/IRender
    (render [_]
      (dom/audio #js {:autoPlay true
                      :ref "audio"}))))

(defn rtc [app owner]
  (reify
    om/IDisplayName (display-name [_] "RTC")
    om/IRender
    (render [_]
      (apply dom/div nil
             (for [{:keys [stream]} (vals (get-in app [:subscribers :info]))
                   :when stream]
               (om/build audio
                         stream
                         {:react-key (aget stream "id")}))))))
