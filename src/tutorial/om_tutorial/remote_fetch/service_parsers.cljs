(ns om-tutorial.remote-fetch.service-parsers
  (:require-macros [cljs.test :refer [is]]
                   [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [cljs.core.async :as async :refer [<! >! put! close!]]
            [cljs.core.async.impl.protocols :refer [Channel]]
            [cljs.reader :as r]
            [goog.object :as gobj]
            [goog.string :as gstr]
            [devcards.util.edn-renderer :refer [html-edn]]
            [facebook-parse.core :as fp]
            [om-tutorial.queries.query-editing :as qe]))

(fp/initialize "dYsID1Jl7CtgN5w1NufHpvTNasvcbhf1Ot4qEHFU" "a1qk48LSuIjTN8QTADASFwubd17EiguEgHIG1rfO")

(defn chan? [v] (satisfies? Channel v))

(defn read-chan-values [m]
  (if (first (filter chan? (vals m)))
    (let [c (async/promise-chan)
          in (async/to-chan m)]
      (go-loop [out {}]
        (if-let [[k v] (<! in)]
          (recur (assoc out k (if (chan? v) (<! v) v)))
          (>! c out)))
      c)
    (go m)))

(def parse-inspector
  (fn [state _]
    (let [{:keys [v error]} @state
          trace (atom [])
          read-tracking (fn [env k params]
                          (swap! trace conj {:env          (assoc env :parser :function-elided)
                                             :dispatch-key k
                                             :params       params}))
          parser (om/parser {:read read-tracking})]
      (dom/div nil
        (when error
          (dom/div nil (str error)))
        (dom/input #js {:type     "text"
                        :value    v
                        :onChange (fn [evt] (swap! state assoc :v (.. evt -target -value)))})
        (dom/button #js {:onClick #(try
                                    (reset! trace [])
                                    (swap! state assoc :error nil)
                                    (parser {:state {:app-state :your-app-state-here}} (r/read-string v))
                                    (swap! state assoc :result @trace)
                                    (catch js/Error e (swap! state assoc :error e))
                                    )} "Run Parser")
        (dom/h4 nil "Parsing Trace")
        (html-edn (:result @state))))))

(defui RemoteQueryConsole
  Object
  (render [this]
    (let [{:keys [parser value]} (om/props this)
          {:keys [query output]} (om/get-state this)
          query (or query value)]
      (dom/div nil
        (qe/code-mirror {:value     query
                         :options   qe/cm-opts
                         :on-change #(om/update-state! this assoc :query %)})
        (dom/button #js {:onClick #(go (om/update-state! this assoc :output (<! (read-chan-values (parser {} (r/read-string query))))))} "Run Query")
        (html-edn (or output ""))))))

(def remote-query-console (om/factory RemoteQueryConsole))

;; Parser 0

(defn parse-read [_ key _]
  (cond
    (= "class" (namespace key))
    {:value (fp/query (name key) [])}))

;; Parser 1

(defn parse-read-1 [_ key _]
  (cond
    (= "class" (namespace key))
    {:value (go
              (->> (fp/query (name key) []) <!
                   (into [] (map #(-> {:db/id (gobj/get % "id")})))))}))

(def parser-1 (om/parser {:read parse-read-1}))

;; Parser 2

(defn parse-object-2 [object children]
  (letfn [(compute-attribute [m {:keys [dispatch-key] :as attr}]
            (assoc m dispatch-key (fp/obj-get object (name dispatch-key))))]
    (-> (reduce compute-attribute {} children)
        (assoc :db/id (gobj/get object "id")))))

(defn parse-read-2 [{:keys [ast]} key _]
  (let [{:keys [children]} ast]
    (cond
      (= "class" (namespace key))
      {:value (go
                (->> (fp/query (name key) []) <!
                     (into [] (map #(parse-object-2 % children)))))})))

(def parser-2 (om/parser {:read parse-read-2}))

;; Parser 3

(defn parse-read-3 [{:keys [ast]} key _]
  (let [{:keys [children]} ast]
    (cond
      (= "class" (namespace key))
      {:value (go
                (->> (fp/query (name key) [[:select (map :dispatch-key children)]]) <!
                     (into [] (map #(parse-object-2 % children)))))})))

;; Parser 4

(defn parse-object-4 [object children]
  (letfn [(compute-attribute [m {:keys [dispatch-key] :as attr}]
            (assoc m dispatch-key (fp/obj-get object (name dispatch-key))))]
    (-> (reduce compute-attribute {} children)
        (assoc :db/id         (gobj/get object "id")
               :db/created-at (gobj/get object "createdAt")
               :db/updated-at (gobj/get object "updatedAt")
               :parse/class   (gobj/get object "className")))))

(defn parse-read-4 [{:keys [ast]} key _]
  (let [{:keys [children]} ast]
    (cond
      (= "class" (namespace key))
      {:value (go
                (->> (fp/query (name key) [[:select (map :dispatch-key children)]]) <!
                     (into [] (map #(parse-object-4 % children)))))})))

;; Parser 5

(declare parse-object-5)

(defn parse-object-attribute-5 [object {:keys [dispatch-key]}]
  (let [value (fp/obj-get object (name dispatch-key))]
    (if (aget value "className")
      (parse-object-5 value nil)
      value)))

(defn parse-object-5 [object children]
  (letfn [(compute-attribute [m {:keys [dispatch-key] :as ast}]
            (assoc m dispatch-key (parse-object-attribute-5 object ast)))]
    (-> (reduce compute-attribute {} children)
        (assoc :db/id         (gobj/get object "id")
               :db/created-at (gobj/get object "createdAt")
               :db/updated-at (gobj/get object "updatedAt")
               :parse/class   (gobj/get object "className")))))

(defn parse-read-5 [{:keys [ast]} key _]
  (let [{:keys [children]} ast]
    (cond
      (= "class" (namespace key))
      {:value (go
                (->> (fp/query (name key) [[:select (map :dispatch-key children)]]) <!
                     (into [] (map #(parse-object-5 % children)))))})))

;; Parser 6

(declare parse-object-6)

(defn parse-object-attribute-6 [object {:keys [dispatch-key children]}]
  (let [value (fp/obj-get object (name dispatch-key))]
    (if (some-> value (aget "className"))
      (parse-object-6 value children)
      value)))

(defn parse-object-6 [object children]
  (letfn [(compute-attribute [m {:keys [dispatch-key] :as ast}]
            (assoc m dispatch-key (parse-object-attribute-6 object ast)))]
    (-> (reduce compute-attribute {} children)
        (assoc :db/id         (gobj/get object "id")
               :db/created-at (gobj/get object "createdAt")
               :db/updated-at (gobj/get object "updatedAt")
               :parse/class   (gobj/get object "className")))))

(defn parse-read-6 [{:keys [ast]} key _]
  (let [{:keys [children]} ast]
    (cond
      (= "class" (namespace key))
      {:value (go
                (->> (fp/query (name key) [[:select (map :dispatch-key children)]]) <!
                     (into [] (map #(parse-object-6 % children)))))})))

;; Parser 7

(defn extract-includes-7 [children]
  (into [] (comp (filter #(= (:type %) :join))
                 (map #(-> [:include (-> % :key name)])))
        children))

(defn parse-read-7 [{:keys [ast]} key _]
  (let [{:keys [children]} ast]
    (cond
      (= "class" (namespace key))
      {:value (go
                (->> (fp/query (name key) (concat [[:select (map :dispatch-key children)]]
                                                  (extract-includes-7 children))) <!
                     (into [] (map #(parse-object-6 % children)))))})))

;; Parser 8

(declare parse-object-8)

(defn parse-object-attribute-8 [object {:keys [dispatch-key children]}]
  (let [value (fp/obj-get object (name dispatch-key))]
    (cond
      (some-> value (aget "className"))
      (parse-object-8 value children)

      (and (not (nil? (namespace dispatch-key)))
           (gstr/startsWith (name dispatch-key) "_"))
      (let [class (namespace dispatch-key)
            field (.substr (name dispatch-key) 1)]
        (fp/query class [[:equal-to field object]]))

      :else value)))

(defn parse-object-8 [object children]
  (letfn [(compute-attribute [m {:keys [dispatch-key] :as ast}]
            (assoc m dispatch-key (parse-object-attribute-8 object ast)))]
    (-> (reduce compute-attribute {} children)
        (assoc :db/id         (gobj/get object "id")
               :db/created-at (gobj/get object "createdAt")
               :db/updated-at (gobj/get object "updatedAt")
               :parse/class   (gobj/get object "className")))))

(defn parse-read-8 [{:keys [ast]} key _]
  (let [{:keys [children]} ast]
    (cond
      (= "class" (namespace key))
      {:value (go
                (->> (fp/query (name key) (concat [[:select (map :dispatch-key children)]]
                                                  (extract-includes-7 children))) <!
                     (into [] (map #(parse-object-8 % children)))))})))

;; Parser 9

(declare parse-object-9)

(defn query-class-9 [class query children]
  (go
    (->> (fp/query class (concat [[:select (map :dispatch-key children)]]
                                 (extract-includes-7 children)
                                 query)) <!
         (into [] (map #(parse-object-9 % children))))))

(defn parse-object-attribute-9 [object {:keys [dispatch-key children]}]
  (let [value (fp/obj-get object (name dispatch-key))]
    (cond
      (some-> value (aget "className"))
      (parse-object-8 value children)

      (and (not (nil? (namespace dispatch-key)))
           (gstr/startsWith (name dispatch-key) "_"))
      (let [class (namespace dispatch-key)
            field (.substr (name dispatch-key) 1)]
        (query-class-9 class [[:equal-to field object]] children))

      :else value)))

(defn parse-object-9 [object children]
  (letfn [(compute-attribute [m {:keys [dispatch-key] :as ast}]
            (assoc m dispatch-key (parse-object-attribute-9 object ast)))]
    (-> (reduce compute-attribute {} children)
        (assoc :db/id         (gobj/get object "id")
               :db/created-at (gobj/get object "createdAt")
               :db/updated-at (gobj/get object "updatedAt")
               :parse/class   (gobj/get object "className")))))

(defn parse-read-9 [{:keys [ast]} key _]
  (let [{:keys [children]} ast]
    (cond
      (= "class" (namespace key))
      {:value (query-class-9 (name key) [] children)})))

;; Parser 10

(declare parse-object-10)

(defn query-class-10 [class query children]
  (go
    (->> (fp/query class (concat [[:select (map :dispatch-key children)]]
                                 (extract-includes-7 children)
                                 query)) <!
         (into [] (map #(parse-object-10 % children))))))

(defn parse-object-attribute-10 [object {:keys [dispatch-key children]}]
  (let [value (fp/obj-get object (name dispatch-key))]
    (cond
      (some-> value (aget "className"))
      (parse-object-10 value children)

      (and (not (nil? (namespace dispatch-key)))
           (gstr/startsWith (name dispatch-key) "_"))
      (let [class (namespace dispatch-key)
            field (.substr (name dispatch-key) 1)]
        (query-class-10 class [[:equal-to field object]] children))

      :else value)))

(defn parse-object-10 [object children]
  (letfn [(compute-attribute [m {:keys [dispatch-key] :as ast}]
            (assoc m dispatch-key (parse-object-attribute-10 object ast)))]
    (-> (reduce compute-attribute {} children)
        (assoc :db/id         (gobj/get object "id")
               :db/created-at (gobj/get object "createdAt")
               :db/updated-at (gobj/get object "updatedAt")
               :parse/class   (gobj/get object "className"))
        (read-chan-values))))

(defn parse-read-10 [{:keys [ast]} key _]
  (let [{:keys [children]} ast]
    (cond
      (= "class" (namespace key))
      {:value (query-class-10 (name key) [] children)})))

;; Parser 11

(declare parse-object-11)

(defn query-class-11 [class query children]
  (go
    (let [res (<! (fp/query class (concat [[:select (map :dispatch-key children)]]
                                          (extract-includes-7 children)
                                          query)))
          out (async/chan 64)]
      (async/pipeline-async 10 out
                            (fn [x c]
                              (go
                                (>! c (<! (parse-object-11 x children)))
                                (close! c)))
                            (async/to-chan res))
      (<! (async/into [] out)))))

(defn parse-object-attribute-11 [object {:keys [dispatch-key children]}]
  (let [value (fp/obj-get object (name dispatch-key))]
    (cond
      (some-> value (aget "className"))
      (parse-object-11 value children)

      (and (not (nil? (namespace dispatch-key)))
           (gstr/startsWith (name dispatch-key) "_"))
      (let [class (namespace dispatch-key)
            field (.substr (name dispatch-key) 1)]
        (query-class-11 class [[:equal-to field object]] children))

      :else value)))

(defn parse-object-11 [object children]
  (letfn [(compute-attribute [m {:keys [dispatch-key] :as ast}]
            (assoc m dispatch-key (parse-object-attribute-11 object ast)))]
    (-> (reduce compute-attribute {} children)
        (assoc :db/id         (gobj/get object "id")
               :db/created-at (gobj/get object "createdAt")
               :db/updated-at (gobj/get object "updatedAt")
               :parse/class   (gobj/get object "className"))
        (read-chan-values))))

(defn parse-read-11 [{:keys [ast]} key _]
  (let [{:keys [children]} ast]
    (cond
      (= "class" (namespace key))
      {:value (query-class-11 (name key) [] children)})))

;; Parser 12

(defmulti read-parse-class-12 (fn [object key] [(gobj/get object "className") key]))

(defmethod read-parse-class-12 :default
  [_ _] nil)

(defmethod read-parse-class-12 ["Person" :directed-count]
  [object _]
  (fp/count "Movie" [[:equal-to "director" object]]))

(declare parse-object-12)

(defn query-class-12 [class query children]
  (go
    (let [res (<! (fp/query class (concat [[:select (map :dispatch-key children)]]
                                          (extract-includes-7 children)
                                          query)))
          out (async/chan 64)]
      (async/pipeline-async 10 out
                            (fn [x c]
                              (go
                                (>! c (<! (parse-object-12 x children)))
                                (close! c)))
                            (async/to-chan res))
      (<! (async/into [] out)))))

(defn parse-object-attribute-12 [object {:keys [dispatch-key children]}]
  (let [value (fp/obj-get object (name dispatch-key))]
    (cond
      (some-> value (aget "className"))
      (parse-object-12 value children)

      (and (not (nil? (namespace dispatch-key)))
           (gstr/startsWith (name dispatch-key) "_"))
      (let [class (namespace dispatch-key)
            field (.substr (name dispatch-key) 1)]
        (query-class-12 class [[:equal-to field object]] children))

      :else (or (read-parse-class-12 object dispatch-key) value))))

(defn parse-object-12 [object children]
  (letfn [(compute-attribute [m {:keys [dispatch-key] :as ast}]
            (assoc m dispatch-key (parse-object-attribute-12 object ast)))]
    (-> (reduce compute-attribute {} children)
        (assoc :db/id         (gobj/get object "id")
               :db/created-at (gobj/get object "createdAt")
               :db/updated-at (gobj/get object "updatedAt")
               :parse/class   (gobj/get object "className"))
        (read-chan-values))))

(defn parse-read-12 [{:keys [ast]} key _]
  (let [{:keys [children]} ast]
    (cond
      (= "class" (namespace key))
      {:value (query-class-12 (name key) [] children)})))

;; Parser 13

(defn start-of-year []
  (let [date (js/Date.)]
    (js/Date. (str (.getFullYear date) "-01-01T00:00:00.000Z"))))

(defn parse-read-13 [{:keys [ast]} key _]
  (let [{:keys [children]} ast]
    (case key
      :app/recent-movies
      {:value (query-class-12 "Movie" [[:greater-than "releaseDate" (start-of-year)]]
                              children)}
      
      (cond
        (= "class" (namespace key))
        {:value (query-class-12 (name key) [] children)}))))

;; UI

(defn parse-ident [{:keys [parse/class db/id]}]
  [(keyword class "by-id") id])

(defui Director
  om/Ident
  (ident [_ props] (parse-ident props))
  
  om/IQuery
  (query [_] [:db/id :parse/class :name])
  
  Object
  (render [this]
    (let [{:keys [name]} (om/props this)]
      (dom/div nil
        "Director: " name))))

(def director-ui (om/factory Director {:keyfn parse-ident}))

(defui MovieUI
  om/Ident
  (ident [_ props] (parse-ident props))
  
  om/IQuery
  (query [_] [:db/id :parse/class :title {:director (om/get-query Director)}])
  
  Object
  (render [this]
    (let [{:keys [title director]} (om/props this)]
      (dom/div nil
        "Movie " title
        (director-ui director)))))

(def movie (om/factory MovieUI {:keyfn parse-ident}))

(defui MovieApp
  om/IQuery
  (query [_] [{:class/Movie (om/get-query MovieUI)}])
  
  Object
  (render [this]
    (let [{movies :class/Movie} (om/props this)]
      (apply dom/div nil (map movie movies)))))

(defn local-read [{:keys [state query]} key params]
  (let [value (get @state key)]
    (if value
      {:value (om/db->tree query value @state)}
      {:remote true})))

(def parser (om/parser {:read local-read}))

(def remote-parser (om/parser {:read parse-read-13}))

(def reconciler
  (om/reconciler {:state  {}
                  :parser parser
                  :send   (fn [{:keys [remote]} cb]
                            (go
                              (let [{:keys [query rewrite]} (om/process-roots remote)
                                    response (<! (read-chan-values (remote-parser {} query)))]
                                (cb (rewrite response)))))}))
