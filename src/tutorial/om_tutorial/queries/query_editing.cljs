(ns om-tutorial.queries.query-editing
  (:require [om.next :as om :refer-macros [defui]]
            [cljs.reader :as r]
            [devcards.util.edn-renderer :refer [html-edn]]
            [cljs.pprint :as pp :refer [pprint]]
            [cljsjs.codemirror]
            [cljsjs.codemirror.mode.clojure]
            [cljsjs.codemirror.addons.matchbrackets]
            [cljsjs.codemirror.addons.closebrackets]
            [om.dom :as dom]))

(defn run-query [db q]
  (try
    (om/db->tree (r/read-string q) db db)
    (catch js/Error e "Invalid Query")))

(def cm-opts
  {:fontSize          8
   :lineNumbers       true
   :matchBrackets     true
   :autoCloseBrackets true
   :indentWithTabs    false
   :mode              {:name "clojure"}})

(defn pprint-src
  "Pretty print src for CodeMirro editor.
  Could be included in textarea->cm"
  [s]
  (-> s
      r/read-string
      pprint
      with-out-str))

(defn textarea->cm
  "Decorate a textarea with a CodeMirror editor given an node and options."
  [node options]
  (js/CodeMirror
    #(.replaceChild (.-parentNode node) % node)
    (clj->js options)))

(defui CodeMirror
  Object
  (componentDidMount [this]
    (let [{:keys [value on-change options]} (om/props this)
          node (js/ReactDOM.findDOMNode (om/react-ref this "textarea"))
          src (pprint-src value)
          cm (textarea->cm node (assoc options :value src))]
      (.on cm "change" #(if (fn? on-change) (on-change (.getValue %))))
      (om/update-state! this assoc :cm cm)))

  (render [this]
    (dom/div nil
      (dom/textarea #js {:ref "textarea"}))))

(def code-mirror (om/factory CodeMirror))

(defui QueryEditor
       Object
       
       (render [this]
               (let [props (om/props this)]
                 (dom/div nil
                          (dom/h4 nil "Database")
                          (html-edn (:db @props))
                          (dom/hr nil)
                          (dom/h4 nil "Query Editor")
                          (code-mirror {:value (:query @props)
                                        :on-change #(swap! props assoc :query %)
                                        :options cm-opts})
                          (dom/button #js {:onClick #(let [query (:query @props)]
                                                      (swap! props assoc :query-result (run-query (:db @props) query)
                                                             :query query))} "Run Query")
                          (dom/hr nil)
                          (dom/h4 nil "Query Result")
                          (html-edn (:query-result @props))))))

(def query-editor (om/factory QueryEditor))

