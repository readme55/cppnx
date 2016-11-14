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

(defn tour-go
  [app-state]
  (swap! app-state assoc :animating? true
         :last-rendered (.getTime (js/Date.)))
  (animate/animate
   app-state
   (fn [state]
     (let [time-now (.getTime (js/Date.))
           elapsed (- time-now (:last-rendered state))
           seconds-per-move 1.0]
       (-> state
           (update :cppn cppnx/step-weights-tour
                   (/ elapsed 1000.0 seconds-per-move))
           (assoc :last-rendered time-now))))
   (fn [state]
     ;; react handles redrawing
     nil)))

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
                    (println "select event" m)
                    (swap! ui-state assoc :selection (:node m))
                    s)
                  :link
                  (fn [s]
                    (println "link event " m)
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
            freeze? (:tour cppn)
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
                            (swap-advance! app-state update :cppn
                                           cppnx/init-isolated-weights-tour)
                            (tour-go app-state))
                :disabled disabled}
               "Simple weight tour"]]
            [:div.col-sm-3
              [:button.btn.btn-default
               {:on-click (fn [e]
                            (swap-advance! app-state update :cppn
                                           cppnx/init-pair-weights-tour)
                            (tour-go app-state))
                :disabled disabled}
               "Pair weight tour"]]
            (when (:tour cppn)
              [:div.col-sm-3
                [:button.btn.btn-danger
                 {:on-click (fn [e]
                              (swap-advance! app-state
                                             (fn [state]
                                               (-> (update state :cppn dissoc :tour)
                                                   (dissoc :animating? :last-rendered)))))}
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
        {:style {:border "1px black"
                 :width "600px"
                 :height "600px"}}
        600 600
        [app-state]
        (fn [gl]
          (let [pgm (gl-img/setup gl @app-state)]
            (gl-img/render gl pgm (:weights @app-state))))]]]]))

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
