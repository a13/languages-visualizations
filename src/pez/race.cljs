(ns pez.race
  (:require
   [clojure.set :as set]
   [clojure.walk :as walk]
   [gadget.inspector :as inspector]
   [pez.benchmark-data :as bd]
   [pez.config :as conf]
   [pez.views :as views]
   [quil.core :as q]
   [quil.middleware :as m]
   [replicant.dom :as d]))

(defonce !app-state (atom {:benchmark :loops
                           :snapshot-mode? false
                           :filter-champions? false
                           :min-track-time-choice "fastest-language"}))

(def app-el (js/document.getElementById "app"))

(def drawing-width 700)
(def language-labels-x 140)
(def ball-width 44)
(def half-ball-width (/ ball-width 2))
(def start-line-x (+ language-labels-x half-ball-width 10))

(def pre-startup-wait-ms 1500)

(defn active-benchmarks [benchmarks]
  (sort-by #(.indexOf [:loops :fibonacci :levenshtein :hello-world] %)
           (reduce-kv (fn [acc _k v]
                        (into acc (remove (fn [benchmark]
                                            (.endsWith (name benchmark) "-hello-world"))
                                          (keys v))))
                      #{}
                      benchmarks)))

(defn benchmark-times [{:keys [benchmark]}]
  (let [benchmarks (filter (comp benchmark second) bd/benchmarks)]
    (->> benchmarks
         vals
         (map benchmark))))

(comment
  (benchmark-times {:benchmark :loops})
  :rcf)

(defn languages []
  (mapv (fn [{:keys [language-file-name] :as lang}]
          (merge lang
                 (bd/benchmarks language-file-name)))
        conf/languages))

(defn fastest-implementation [{:keys [benchmark]} implementations]
  (apply min-key benchmark implementations))

(defn best-languages [{:keys [benchmark filter-champions?] :as app-state}]
  (let [langs (languages)]
    (if filter-champions?
      (->> langs
           (group-by :language)
           vals
           (map (fn [champions]
                  (fastest-implementation app-state (filter benchmark champions))))
           (filter (fn [lang]
                     (benchmark lang))))
      (filter (fn [lang]
                (benchmark lang))
              langs))))

(comment
  (best-languages {:benchmark :loops})
  :rcf)

(defn sorted-languages [{:keys [benchmark] :as app-state}]
  (sort-by benchmark (best-languages app-state)))

(defn find-missing-languages []
  (let [config-languages (set (map :language-file-name conf/languages))
        benchmark-languages (set (keys bd/benchmarks))]
    (set/difference benchmark-languages config-languages)))

(comment
  (languages)
  (sorted-languages {:benchmark :loops})
  (find-missing-languages)
  :rcf)

(defn dims [app-state]
  [(min drawing-width (.-offsetWidth app-el)) (+ 80 (* 45 (count (sorted-languages app-state))))])

(defn arena [width height]
  (let [finish-line-x (- width half-ball-width 5)]
    {:width width
     :finish-line-x finish-line-x
     :track-length (- finish-line-x start-line-x)
     :middle-x (/ width 2)
     :middle-y (/ height 2)}))

(defn setup [{:keys [benchmark min-track-time-choice] :as app-state}]
  (q/frame-rate 120)
  (q/image-mode :center)
  (let [arena (arena (q/width) (q/height))
        min-time (apply min (benchmark-times app-state))]
    (merge arena
           {:t 0
            :benchmark benchmark
            :race-started? false
            :benchmark-title (benchmark conf/benchmark-names)
            :min-track-time-ms (if (= "fastest-language" min-track-time-choice)
                                 min-time
                                 (parse-long min-track-time-choice))
            :languages (mapv (fn [i lang]
                               (let [benchmark-time (benchmark lang)
                                     speed (/ min-time benchmark-time)]
                                 (merge lang
                                        {:speed speed
                                         :runs 0
                                         :track-x start-line-x
                                         :greeting "Hello, World!"
                                         :benchmark-time benchmark-time
                                         :benchmark-time-str (str (-> benchmark-time
                                                                      (.toFixed 1)
                                                                      (.padStart 10))
                                                                  "ms")
                                         :x 0
                                         :y (+ 90 (* i 45))
                                         :logo-image (q/load-image (:logo lang))})))
                             (range)
                             (sorted-languages app-state))})))

(comment
  (setup :loops)
  (-> 234.0 (.toFixed 1) (.padStart 7))
  :rcf)

(defn update-draw-state [{:keys [track-length min-track-time-ms] :as draw-state}
                         {:keys [elapsed-ms snapshot-mode?] :as _app-state}]
  (let [arena (arena (q/width) (q/height))
        race-started? (> elapsed-ms pre-startup-wait-ms)
        position-time (- elapsed-ms pre-startup-wait-ms)
        first-lang (first (:languages draw-state))
        take-snapshot? (and snapshot-mode?
                            (= 1 (:runs first-lang))
                            (not (:snapshot-taken? draw-state)))]
    (merge draw-state
           arena
           {:t elapsed-ms
            :race-started? race-started?
            :snapshot-taken? (or (:snapshot-taken? draw-state) take-snapshot?)
            :take-snapshot? take-snapshot?}
           {:languages (mapv (fn [{:keys [speed] :as lang}]
                               (merge lang
                                      (when race-started?
                                        (let [normalized-time (/ position-time min-track-time-ms)
                                              scaled-time (* normalized-time speed)
                                              distance (* track-length scaled-time)
                                              loop-distance (mod distance (* 2 track-length))
                                              x (if (> loop-distance track-length)
                                                  (- (* 2 track-length) loop-distance)
                                                  loop-distance)]
                                          {:track-x (+ start-line-x x)
                                           :runs (quot distance track-length)}))))
                             (:languages draw-state))})))

(comment
  (q/no-loop)
  :rcf)

(def offwhite 245)
(def darkgrey 120)
(def black 40)

(declare event-handler)

(defn draw! [{:keys [benchmark-title middle-x
                     take-snapshot? benchmark] :as draw-state}]
  (when take-snapshot?
    (event-handler {} [[:ax/take-snapshot benchmark]]))
  (q/background offwhite)
  (q/stroke-weight 0)
  (q/text-align :center)
  (q/fill black)
  (q/text-size 20)
  (q/text-style :bold)
  (q/text benchmark-title middle-x 20)
  (q/text-size 16)
  (q/text-style :normal)
  (q/text "How fast is your favorite language?" middle-x 45)
  (doseq [lang (:languages draw-state)]
    (let [{:keys [language-name logo-image y track-x runs benchmark-time-str]} lang]
      (q/text-style :normal)
      (q/text-align :right :center)
      (q/fill darkgrey)
      (q/rect 0 (- y 12) (+ language-labels-x 5) 24)
      (q/fill offwhite)
      (q/text-size 14)
      (q/text language-name language-labels-x y)
      (q/fill darkgrey)
      (q/text-align :left)
      (q/text benchmark-time-str 5 (- y 20))
      (q/text-align :right)
      (q/text-num runs language-labels-x (- y 20))
      (q/image logo-image track-x y ball-width ball-width))))

(defn save-image [benchmark]
  (println "Saving file...")
  (q/save (str "languages-visualizations-" (name benchmark) ".png")))

(defn save-handler
  [benchmark]
  (fn [state {:keys [key _key-code]}]
    (case key
      (:s) (do
             (event-handler {} [[:ax/take-snapshot benchmark]])
             state)
      state)))

(defn- share! [site text]
  (let [url (-> js/window .-location .-href)]
    (.open js/window (str (case site
                            :site/x "https://twitter.com/intent/tweet?text="
                            :site/linkedin "https://www.linkedin.com/shareArticle?mini=true&text=")
                          (js/encodeURIComponent text)
                          "&url="
                          (js/encodeURIComponent url)))))
(defn run-sketch []
  ; TODO: Figure out if there's a way to set the current applet with public API
  #_{:clj-kondo/ignore [:unresolved-namespace]}
  (set! quil.sketch/*applet*
        (let [start-time (js/performance.now)]
          (q/sketch
           :host "race"
           :size (dims @!app-state)
           :renderer :p2d
           :setup (fn [] (setup @!app-state))
           :update (fn [state]
                     (let [elapsed-ms (- (js/performance.now) start-time)]
                       (update-draw-state state (assoc @!app-state :elapsed-ms elapsed-ms))))
           :draw draw!
           :key-pressed (save-handler (:benchmark @!app-state))
           :middleware [m/fun-mode]))))

(defn- enrich-action-from-replicant-data [{:replicant/keys [js-event]} actions]
  (walk/postwalk
   (fn [x]
     (if (keyword? x)
       (cond (= :event/target.value x) (some-> js-event .-target .-value)
             :else x)
       x))
   actions))

(defn- action-handler [{state :new-state :as result} replicant-data action]
  (when js/goog.DEBUG
    (js/console.debug "Triggered action" action))
  (let [[action-name & args :as enriched] (enrich-action-from-replicant-data replicant-data action)
        _ (js/console.debug "Enriched action" enriched)
        {:keys [new-state effects]} (cond
                                      (= :ax/set-hash action-name)
                                      {:effects [[:fx/set-hash (first args)]]}

                                      (= :ax/take-snapshot action-name)
                                      {:effects [[:fx/take-snapshot (first args)]]}

                                      (= :ax/set-benchmark action-name)
                                      {:new-state (assoc state :benchmark (keyword (first args)))
                                       :effects [[:fx/run-sketch]]}

                                      (= :ax/set-min-track-time-choice action-name)
                                      {:new-state (assoc state :min-track-time-choice (first args))
                                       :effects [[:fx/run-sketch]]}

                                      (= :ax/toggle-snapshot-mode action-name)
                                      {:new-state (update state :snapshot-mode? not)}

                                      (= :ax/toggle-champions-mode action-name)
                                      {:new-state (update state :filter-champions? not)
                                       :effects [[:fx/run-sketch]]}

                                      (= :ax/share action-name)
                                      {:effects [[:fx/share (first args) (second args)]]})]
    (cond-> result
      new-state (assoc :new-state new-state)
      effects (update :effects into effects))))

(defn- event-handler [replicant-data actions]
  (let [{:keys [new-state effects]} (reduce (fn [result action]
                                              (action-handler result replicant-data action))
                                            {:new-state @!app-state
                                             :effects []}
                                            actions)]
    (when new-state
      (reset! !app-state new-state))
    (when effects
      (doseq [effect effects]
        (when js/goog.DEBUG
          (js/console.debug "Triggered effect" effect))
        (let [[effect-name & args] effect]
          (cond
            (= :fx/console.log effect-name) (apply js/console.log args)
            (= :fx/set-hash effect-name) (set! (-> js/window .-location .-hash) (first args))
            (= :fx/run-sketch effect-name) (run-sketch)
            (= :fx/take-snapshot effect-name) (save-image (first args))
            (= :fx/share effect-name) (apply share! args)))))))

(defn render-app! [el state]
  (d/render el (views/app state (active-benchmarks bd/benchmarks))))

(defn handle-hash []
  (let [hash (-> js/window .-location .-hash)
        benchmark (when (seq hash)
                    (keyword (subs hash 1)))]
    (if (contains? (set (active-benchmarks bd/benchmarks)) benchmark)
      (event-handler {} [[:ax/set-benchmark benchmark]])
      (event-handler {} [[:ax/set-benchmark :loops]]))))

(defn ^:dev/after-load start []
  (js/console.log "start")
  (render-app! app-el @!app-state))

(defn ^:export init! []
  (js/console.log "init")
  (inspector/inspect "App state" !app-state)
  (add-watch !app-state :update (fn [_k _r _o n]
                                  (render-app! app-el n)))
  (js/window.addEventListener "resize"
                              (fn [_e]
                                (let [[w h] (dims @!app-state)]
                                  (q/resize-sketch w h))))
  (d/set-dispatch! event-handler)
  (start)
  (handle-hash)
  (js/window.addEventListener "hashchange" handle-hash)
  (run-sketch))

(defn ^{:export true
        :dev/before-load true} stop []
  (js/console.log "stop"))
