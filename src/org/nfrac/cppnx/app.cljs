(ns org.nfrac.cppnx.app
  (:require [org.nfrac.cppnx.core :as cppnx]
            [org.nfrac.cppnx.helpers :refer [glcanvas]]
            [org.nfrac.cppnx.webgl-image :as gl-img]
            [org.nfrac.cppnx.webgl-lines :as gl-lines]
            [org.nfrac.cppnx.animate :as animate]
            [org.nfrac.cppnx.svg :as svg]
            [fipp.edn]
            [monet.canvas :as c]
            [reagent.core :as reagent :refer [atom]]
            [goog.dom :as dom]
            [goog.webgl :as ggl]
            [clojure.core.async :as async :refer [<! put!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(enable-console-print!)

(defonce app-state
  (atom {:cppn cppnx/example-cppn}))

(defonce ui-state
  (atom {:selection nil}))

(defonce undo-buffer
  (atom ()))

(defonce redo-buffer
  (atom ()))

(defn swap-advance!
  "ref = app-state"
  [ref f & more]
  ;; record state for undo
  (swap! undo-buffer conj @ref)
  (when (seq @redo-buffer)
    (reset! redo-buffer ()))
  (apply swap! ref f more))

(def gl-canvas-class "cppnx-main-canvas")

(defn animate [state step-fn draw-fn]
  (js/requestAnimationFrame
   (fn [time]
     (when-not (:stop! @state)
       (let [next-value (swap! state step-fn)]
         (draw-fn next-value)
         (animate state step-fn draw-fn))))))

;; TODO: keep history of weight targets, allow reverse (fleeting glimpses!)

(defn tour-go!
  [app-state ui-state tour]
  (let [gl-info-ref (clojure.core/atom {})
        el (dom/getElementByClass gl-canvas-class)
        gl (.getContext el "webgl")]
    (swap! ui-state assoc :animating? true
           :gl-info-ref gl-info-ref
           :anim-start (.getTime (js/Date.)))
    (reset! gl-info-ref
            (assoc (gl-img/setup gl @app-state)
                   :tour tour
                   :last-rendered (.getTime (js/Date.))))
    (animate
     gl-info-ref
     (fn [info]
       (let [time-now (.getTime (js/Date.))
             elapsed (- time-now (:last-rendered info))
             seconds-per-move 1.3
             dt (/ elapsed 1000.0 seconds-per-move)
             tour (cppnx/step-weights-tour (:tour info) dt)]
         (-> info
             (assoc :tour tour
                    :last-rendered time-now))))
     (fn [info]
       (gl-img/render info (:weights (:tour info)))))))

(defn tour-stop!
  [app-state ui-state]
  (let [gl-info-ref (:gl-info-ref @ui-state)
        weights (:weights (:tour @gl-info-ref))]
    (swap! gl-info-ref assoc :stop! true)
    (swap-advance! app-state
                   update :cppn cppnx/set-cppn-weights weights)
    (swap! ui-state dissoc :animating? :gl-info-ref)))

(defn settings-pane
  [app-state ui-state]
  (let [svg-events-c (async/chan)]
    (go-loop []
      (when-let [m (<! svg-events-c)]
        (let [from (:from m)
              to (:to m)
              f (case (:event m)
                  :select
                  (fn [s]
                    (swap! ui-state assoc :selection (:node m))
                    s)
                  :link
                  (fn [s]
                    (cond
                      (get-in s [:cppn :edges from to])
                      (update-in s [:cppn :edges from] dissoc to)
                      (get-in s [:cppn :edges to from])
                      (update-in s [:cppn :edges to] dissoc from)
                      :else
                      (update s :cppn cppnx/link-nodes from to))))]
           (swap-advance! app-state f))
        (recur)))
    (fn [_ _]
      (let [cppn (:cppn @app-state)
            freeze? (:animating? @ui-state)
            disabled (when freeze? "disabled")]
        [:div
          [:div.row
            [:div.col-lg-12
              [svg/cppn-svg cppn (:selection @ui-state) svg-events-c]]]
          (when-let [sel (:selection @ui-state)]
            [:div.row
             [:div.col-lg-12
              [:div.panel.panel-primary
               [:div.panel-heading
                [:h4.panel-title "Selected node"]]
               [:button.btn.btn-default
                {:on-click (fn [e]
                             (swap-advance! app-state update :cppn
                                            cppnx/delete-node sel))
                 :disabled (when (or freeze? (not (contains? (:nodes cppn) sel)))
                             "disabled")}
                "Delete"]]]])
          [:div.row
            [:div.col-sm-3
              [:button.btn.btn-default
               {:on-click (fn [e]
                            (swap-advance! app-state update :cppn
                                           cppnx/mutate-append-node))
                :disabled disabled}
               "Append node"]]
            [:div.col-sm-3
              [:button.btn.btn-default
               {:on-click (fn [e]
                            (swap-advance! app-state update :cppn
                                           cppnx/mutate-add-conn))
                :disabled disabled}
               "Add connection"]]
            [:div.col-sm-3
              [:button.btn.btn-default
               {:on-click (fn [e]
                            (swap-advance! app-state update :cppn
                                           cppnx/mutate-rewire-conn))
                :disabled disabled}
               "Rewire connection"]]]
          [:div.row
            [:div.col-sm-3
              [:button.btn.btn-default
               {:on-click (fn [e]
                            (swap-advance! app-state update :cppn
                                           cppnx/randomise-weights))
                :disabled disabled}
               "Random weights"]]
            [:div.col-sm-3
              [:button.btn.btn-default
               {:on-click (fn [e]
                            (tour-go! app-state ui-state
                                      (cppnx/init-weights-tour cppn 1)))
                :disabled disabled}
               "Weight tour (ones)"]]
            [:div.col-sm-3
              [:button.btn.btn-primary
               {:on-click (fn [e]
                            (tour-go! app-state ui-state
                                      (cppnx/init-weights-tour cppn 3)))
                :disabled disabled}
               "Weight tour (triples)"]]
            (when (:animating? @ui-state)
              [:div.col-sm-3
                [:button.btn.btn-danger
                 {:on-click (fn [e]
                              (tour-stop! app-state ui-state))}
                 "Stop tour"]])]
          [:div.row
            [:p
              "CPPN data"]
            [:pre
              (with-out-str (fipp.edn/pprint (:cppn @app-state)))]]]))))

(defn view-pane
  [app-state ui-state]
  (let []
    [:div
     [:div.row
      [:div.col-lg-12
       [glcanvas
        {:class gl-canvas-class
         :style {:border "1px black"
                 :width "600px"
                 :height "600px"}}
        600 600
        [app-state]
        (fn [gl]
          (let [info (gl-img/setup gl @app-state)]
            (gl-img/render info (:ws info))))]]]]))

(defn navbar
  [app-state ui-state]
  (let []
    [:nav.navbar.navbar-default
     [:div.container-fluid
      [:div.navbar-header
       [:a.navbar-brand {:href "https://github.com/floybix/cppnx"}
        "cppnx."]]
      [:div
       [:ul.nav.navbar-nav
        ;; step back
        [:li
         [:button.btn.btn-default.navbar-btn
          {:type :button
           :on-click
           (fn [_]
             (let [new-state (peek @undo-buffer)]
               (swap! undo-buffer pop)
               (swap! redo-buffer conj @app-state)
               (reset! app-state new-state)))
           :title "Step backward in time"
           :disabled (when (empty? @undo-buffer) "disabled")}
          [:span.glyphicon.glyphicon-step-backward {:aria-hidden "true"}]
          " Undo"]]
        ;; step forward
        (when-not (empty? @redo-buffer)
          [:li
           [:button.btn.btn-default.navbar-btn
            {:type :button
             :on-click
             (fn [_]
               (let [new-state (peek @redo-buffer)]
                 (swap! redo-buffer pop)
                 (swap! undo-buffer conj @app-state)
                 (reset! app-state new-state)))
             :title "Step forward in time"
             :disabled (when (empty? @redo-buffer) "disabled")}
            [:span.glyphicon.glyphicon-step-forward {:aria-hidden "true"}]
            " Redo"]])
        ;; timestep
        [:li
         [:p.navbar-text
          (str "Foo")]]]]]]))

(defn app-pane
  [app-state ui-state]
  [:div
   [navbar app-state ui-state]
   [:div.container-fluid
    [:div.row
     [:div.col-lg-8.col-md-6
      [view-pane app-state ui-state]]
     [:div.col-lg-4.col-md-6
      [settings-pane app-state ui-state]]]]])

(reagent/render-component [app-pane app-state ui-state]
                          (. js/document (getElementById "app")))

(defn on-js-reload [])
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
