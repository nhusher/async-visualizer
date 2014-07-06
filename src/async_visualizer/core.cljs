(ns async-visualizer.core
  (:require [ om.core :as om  :include-macros true ]
            [ om.dom  :as dom :include-macros true ]
            [ cljs.core.async :as async :refer [<! >! chan] ]
            [ cljs.core.async.impl.protocols :as protocols ]
            [ cljs.core.async.impl.buffers   :as buffers    ]
            [ cljs.core.async.impl.channels  :as channels  ])
  (:require-macros [cljs.core.async.macros :refer [ go ]]))

(enable-console-print!)

(def css-transition-group (-> js/React (aget "addons") (aget "CSSTransitionGroup")))


(def ^:dynamic *unique-id* 0)

(defn uid [] (set! *unique-id* (inc *unique-id*)))

(defn dot
  ([color] (dot color (uid)))
  ([color uid] { :color color :uid uid }))

;;
;; -- Force core.async channels and buffers to reveal their state --
;;

(defprotocol XRay
  (reveal [item]))

(extend-type channels/ManyToManyChannel XRay
  (reveal [c] { :puts  (map #(.-val %) (remove nil? (reveal (.-puts c))))
                :takes (map (fn [i] (dot :sleeping (hash i))) (remove nil? (reveal (.-takes c))))
                :buf  (if (.-buf c) (remove nil? (reveal (.-buf c))) nil) }))

(extend-type buffers/RingBuffer XRay
  (reveal [buf]
          (let [ head (.-head buf)
                 tail (.-tail buf)
                 arr  (.-arr buf)]
            (cond
             (<  tail head) (take head (drop tail arr))
             (>= tail head) (concat (drop tail arr) (take head arr))))))

(extend-type buffers/SlidingBuffer XRay
  (reveal [buf] (reveal (.-buf buf))))

(extend-type buffers/FixedBuffer XRay
  (reveal [buf] (reveal (.-buf buf))))

(extend-type buffers/DroppingBuffer XRay
  (reveal [buf] (reveal (.-buf buf))))

;;
;; -- End awful core.async hackery
;;


(defn color [color owner]
  (om/component
   (let [c (or (:color color) color)]
     (dom/i #js { :className (str "dot " (name c)) } ""))))

(defn dot-group [buf owner]
  (om/component
   (css-transition-group
    (clj->js { :className "buffer"
               :transitionName "dots"
               :children (reverse (map #(om/build color % { :react-key (or (:uid %) (uid)) }) buf))}))))

(defn channel [ch owner]
  (om/component
   (dom/div #js { :className "channel" }
            (dom/div nil "P" (om/build dot-group (:puts ch)))
            (dom/div nil "B" (om/build dot-group (:buf ch)))
            (dom/div nil "T" (om/build dot-group (:takes ch))))))

(defn example-one [data owner]
  (reify
    om/IInitState
    (init-state [_] { :result [] })
    om/IWillMount
    (will-mount [_]
      (let [ch (:ch data)]
        (go
         (loop []
           (om/set-state! owner :result [ (<! ch) ])
           (recur)))))
    om/IRenderState
    (render-state [_ state]
      (let [ ch (:ch data)
             res (:result state)]
        (dom/div #js { :className "example-root" }
                 (dom/div #js { :className "controls" }
                          (dom/button
                           #js { :className "pure-button"
                                 :onClick (fn [_]
                                            (async/put! ch (dot :red))
                                            (om/refresh! owner)) }

                           (dom/code nil ">! ch " (om/build color (dot :red))))
                          " "
                          (dom/button
                           #js { :className "pure-button"
                                 :onClick (fn [_]
                                            (async/put! ch (dot :blue))
                                            (om/refresh! owner))}

                           (dom/code nil ">! ch " (om/build color (dot :blue)))))
                 (om/build dot-group res))))))

(om/root example-one
         (atom { :ch (chan) })
         { :target (js/document.querySelector ".example-one") })


(defn example-two [data owner]
  (om/component
   (let [ ch (:ch data)]
     (dom/div #js { :className "example-root" }
              (dom/div #js { :className "controls" }
                       (dom/button
                        #js { :className "pure-button"
                              :onClick (fn [_]
                                         (async/put! ch (dot :red))
                                         (om/refresh! owner)) }

                        (dom/code nil ">! ch " (om/build color (dot :red))))
                       " "
                       (dom/button
                        #js { :className "pure-button"
                              :onClick (fn [_]
                                         (async/put! ch (dot :blue))
                                         (om/refresh! owner))}

                        (dom/code nil ">! ch " (om/build color (dot :blue)))))
              (dom/div #js { :className "channel-container" }
                       (om/build channel (reveal ch)))

              (dom/div #js { :className "controls" }
                       (dom/button
                        #js { :className "pure-button"
                              :onClick (fn [_]
                                         (async/take! ch #(prn %))
                                         (om/refresh! owner))}
                        (dom/code nil "<! ch")))))))

(om/root example-two
         (atom { :ch (chan (async/sliding-buffer 5)) })
         { :target (js/document.querySelector ".example-two") })
