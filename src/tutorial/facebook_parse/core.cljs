(ns facebook-parse.core
  (:refer-clojure :exclude [update count])
  (:require [cljs.core.async :as async :refer [chan put!]]
            [goog.string :as str]
            [goog.object :as gobj]
            [clojure.walk :as walk]))

(defn- ^:private map-keys [f m] (into {} (map (fn [[k v]] [(f k) v]) m)))

(defn- ^:private post-walk-keys [f form]
  (letfn [(step [x] (if (map? x) (map-keys f x) x))]
    (walk/postwalk step form)))

(defn- ^:private clj->js-options [m]
  (let [f (fn [[k v]] [(str/toCamelCase (name k)) v])]
    (-> (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)
        clj->js)))

(defn- ^:private js-call [obj method args]
  (if-let [f (gobj/get obj method)]
    (.apply f obj (clj->js args))
    (throw (js/Error (str "Method " method " could not be found in " obj)))))

(defn- ^:private load-external-script [url]
  (.appendChild (.-body js/document)
                (doto (.createElement js/document "script")
                  (gobj/set "src" url))))

(def Parse (gobj/get js/window "Parse" #js {}))
(def Object (gobj/get Parse "Object" #js {}))
(def FacebookUtils (gobj/get Parse "FacebookUtils" #js {}))
(def User (gobj/get Parse "User" #js {}))
(def Query (gobj/get Parse "Query"))
(def Cloud (gobj/get Parse "Cloud"))
(def Analytics (gobj/get Parse "Analytics"))
(def ACL (gobj/get Parse "ACL"))

(defn promise->chan [promise]
  (let [c (async/promise-chan)]
    (js-call promise "then" [#(put! c %) #(put! c %)])
    c))

(defn initialize [app-id js-key] (js-call Parse "initialize" [app-id js-key]))

(defn- facebook-init [options]
  (js-call FacebookUtils "init" [(clj->js-options (merge {:xfbml   true
                                                              :cookie  true
                                                              :version "v2.4"} options))]))

(defonce initialize-facebook
  (let [mem (atom nil)]
    (fn [data]
      (if @mem
        (assert (= (:data @mem) data) "Tried to initialize Facebook with different settings, ignoring new settings.")
        (let [c (chan)]
          (gobj/set js/window "fbAsyncInit" #(do
                                              (facebook-init data)
                                              (async/close! c)))
          (load-external-script "//connect.facebook.net/en_US/sdk.js")
          (reset! mem {:data data :channel c})))
      (:channel @mem))))

(defn facebook-login [scope]
  (let [c (chan)]
    (js-call FacebookUtils "logIn" [scope #js {:success #(put! c %)
                                                 :error   #(put! c %2)}])
    c))

(defn logout [] (js-call User "logOut" []))

(defn parse-class [name] (js-call Object "extend" [name]))

(defn current-user [] (js-call User "current" []))

(defn create-acl
  ([] (create-acl nil {}))
  ([to {:keys [public-read public-write]
        :or   {public-read  false
               public-write false}}]
   (let [acl (ACL. to)]
     (js-call acl "setPublicReadAccess" [public-read])
     (js-call acl "setPublicWriteAccess" [public-write])
     acl)))

(defn create
  ([class attributes] (create class attributes {}))
  ([class attributes {:keys [acl]}]
   (let [Class (parse-class class)
         attributes (clj->js (post-walk-keys (comp str/toCamelCase name) attributes))
         obj (Class.)]
     (if acl (js-call obj "setACL" [acl]))
     (promise->chan (js-call obj "save" [attributes])))))

(defn update
  ([class id updates] (update class id updates {}))
  ([class id attributes {:keys [acl]}]
   (let [class (parse-class class)
         attributes (clj->js (post-walk-keys (comp str/toCamelCase name) attributes))
         obj (doto (class.) (gobj/set "id" id))]
     (if acl (js-call obj "setACL" [acl]))
     (promise->chan (js-call obj "save" [attributes])))))

(defn destroy [class id]
  (let [class (parse-class class)
        obj (doto (class.) (gobj/set "id" id))]
    (promise->chan (js-call obj "destroy" []))))

(defn call-methods [object methods]
  (doseq [[cmd & args] methods
          :let [cmd (-> (name cmd) str/toCamelCase)]]
    (js-call object cmd args))
  object)

(defn query* [class query]
  (doto (Query. class)
    (call-methods query)))

(defn count [class query] (promise->chan (js-call (query* class query) "count" [])))
(defn query [class query] (promise->chan (js-call (query* class query) "find" [])))
(defn query-first [class query] (promise->chan (js-call (query* class query) "first" [])))

(defn query-or [queries query]
  (let [q (js-call Query "or" queries)]
    (call-methods q query)
    (promise->chan (js-call q "find" []))))

(defn query-id [class id]
  (let [Class (parse-class class)]
    (doto (Class.) (gobj/set "id" id))))

(defn query-by-id [class id]
  (let [q (Query. class)]
    (promise->chan (js-call q "get" [id]))))

(defn obj-id [obj] (gobj/get obj "id"))

(defn read-acl [object]
  (some-> (js-call object "getACL" [])
          (gobj/get "permissionsById")
          (js->clj)))

(defn can-write? [user {:keys [parse/acl]}]
  (let [user-id (obj-id user)]
    (or (get-in acl ["*" "write"])
        (get-in acl [user-id "write"]))))

(defn obj-get
  ([obj attribute] (js-call obj "get" [attribute]))
  ([obj attribute default] (js-call obj "get" [attribute default])))

(defn cloud-run [cmd args]
  (promise->chan (js-call Cloud "run" [cmd (clj->js args)])))

(defn track
  ([event] (track event {}))
  ([event dimensions]
   (println "Tracking" event (pr-str dimensions))
   (promise->chan (js-call Analytics "track" [event (clj->js dimensions)]))))
