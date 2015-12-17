(ns om-tutorial.G-Remote-Fetch-Service
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
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [facebook-parse.core :as fp]
            [om-tutorial.remote-fetch.service-parsers :as sp]
            [om-tutorial.queries.query-editing :as qe]))

(defcard-doc
  "
  ## Sometimes you don't need a backend
  
  Writing a full backend server can be a lot of work, and many times all you need
  is some remote persistent storage where you can store and retrieve data. On those cases,
  I believe you can have an easier time delegating that to a service, and today we have
  many great services for that.
  
  On this tutorial we are going to use [Parse.com](http://parse.com), which is a company
  that provides platform solutions for developers, we are going to use their database service.
  
  ## Parse.com considerations
  
  In Parse.com what we usually call \"tables\" they call \"classes\", we gonna use their
  convention on the names, so we need to less translation on our minds.
  
  We are going to use some helper functions to deal with Parse specifics, check the namespace
  `facebook-parse.core` for details.
  
  When you read `Parse` with capital `P`, I'm always referring to Parse.com the service.
  
  Reading Parse guides on [objects](https://parse.com/docs/js/guide#objects) and
  [queries](https://parse.com/docs/js/guide#queries) might be helpful to understand this
  tutorial.
  
  If you wanna more information on some specific API of then, please check their
  [API Docs](https://parse.com/docs/js/api/).
  
  ## core.async ahead
  
  To manage the async bits, we are going to use core.async, if you are not familiar with
  it, I suggest you to [read this walkthrough](https://github.com/clojure/core.async/blob/master/examples/walkthrough.clj)
  to get some familiarity with it.
  
  ## Database structure
  
  For this tutorial, we are going to use a tiny movies database that was created just for
  demonstration here. This is the structure of it:
  
  ```
  Movie
  - title : String
  - director : Pointer<Person>
  - releaseDate : Date
  
  Person
  - name : String
  ```
  
  ## Writing the parser
  
  To start our parser, let's define a generic way to list all items of any class in the
  database, using the entry point `[:class/ClassName]`, so if I want to get all the movies
  in the database we should be able by querying `[:class/Movie]`.
  ")

(defcard parser-read-trace
  "
  To understand our implementation first let's understand what this query generates,
  let's look at what the parser gets when we query for `[:class/Movie]`, run the
  parser and read the data:
  "
  sp/parse-inspector
  {:v "[:class/Movie]"})

(defcard-doc
  "
  Now we need to write the parser code to handle this case:

  ```clj
  (defn parse-read [_ key _]
    (cond
      (= \"class\" (namespace key))
      {:value (fp/query (name key) [])}))
  ```
  
  Let's try our parser:
  
  ```clj
  (def parser (om/parser {:read parse-read}))
  
  (parser {} [:class/Movie])
  ```
  
  Result:
  
  ```clj
  {:class/Movie #object[cljs.core.async.impl.channels.ManyToManyChannel]}
  ```
  
  Not really what we need, it's returning a core.async channel as the value, we need
  to get the values of the channels, let's create a helper to handle this, remember that
  out result map may have multiple keys with channels as values:
  
  ```clj
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
  ```
  
  In a nutshell, this function gets a map, convert into a channel where each pair is an
  item, loops though then reading channels when they are found in values and returns
  a promise channel that will resolve to a map with the values resolved. It allows
  us to read our previous data as:
  
  ```clj
  (go
    (-> (parser {} [:class/Movie])
        (read-chan-values) <!
        (print)))
  ```
  
  Check the console and see: 
  
  ```clj
  {:class/Movie #js [#object[e [object Object]] #object[e [object Object]] #object[e [object Object]]]}
  ```
  
  Hum, not quite ready yet. The next step seems to be parsing these object results; it's
  about reading the Parse.com objects and transforming the data we want, here is one
  possible implementation of it that just reads the object ids:
  
  ```clj
  (defn parse-read [_ key _]
    (cond
      (= \"class\" (namespace key))
      {:value (go
                (->> (fp/query (name key) []) <!
                     (into [] (map #(-> {:db/id (gobj/get % \"id\")})))))}))
  ```
  ")

(defcard query-runner-parse-1
  "You can test our current parser bellow:"
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[:class/Movie]"
                                :parser (om/parser {:read sp/parse-read-1})}))))

(defcard-doc
  "
  Next we need to get some real attributes from the models, for that we need to start
  descending our AST, just for clarity run the parser bellow so we can see what we are
  dealing with.
  ")

(defcard parser-read-trace
  sp/parse-inspector
  {:v "[{:class/Movie [:title]}]"})

(defcard-doc
  "
  Comparing to our previous AST, now our main node is a join type, and it has a children
  AST that we can use to get the fields from the object. Let's use that to fetch fields
  from our query:
  
  ```clj
  (defn parse-read [{:keys [ast]} key _]
    (let [{:keys [children]} ast]
      (cond
        (= \"class\" (namespace key))
        {:value (go
                  (->> (fp/query (name key) []) <!
                       (into [] (map #(-> (reduce (fn [m {:keys [dispatch-key]}]
                                                    (assoc m dispatch-key (fp/obj-get % (name dispatch-key))))
                                                  {:db/id (gobj/get % \"id\")}
                                                  children))))))})))
  ```
  
  This is getting too dense, better extract out the object parsing:
  
  ```clj
  (defn parse-object [object children]
    (letfn [(compute-attribute [m {:keys [dispatch-key] :as attr}]
              (assoc m dispatch-key (fp/obj-get object (name dispatch-key))))]
      (-> (reduce compute-attribute {} children)
          (assoc :db/id (gobj/get object \"id\")))))
  
  (defn parse-read [{:keys [ast]} key _]
    (let [{:keys [children]} ast]
      (cond
        (= \"class\" (namespace key))
        {:value (go
                  (->> (fp/query (name key) []) <!
                       (into [] (map #(parse-object % children)))))})))
  ```
  
  That will convert the object into a map based on the children, check the AST up again
  to get a clear picture of the structure of the data.
  ")

(defcard query-runner-parse-2
  "Try to fetch the movie titles:"
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:class/Movie [:title]}]"
                                :parser (om/parser {:read sp/parse-read-2})}))))

(defcard-doc
  "
  That's cool; we have real data now. Look at the remote response for that query at the browser
  network and you may see something like this:
  
  ![network](https://www.dropbox.com/s/6jrxwwfijaqo71n/Screenshot%202015-12-17%2010.30.13.png?raw=1)
  
  There is some over fetching of data here, we only care about the `id` and `title`, but
  the request is bringing everything. Let's modify the parser, so we only fetch the fields
  we want, we going to use [Parse select](https://parse.com/docs/js/api/classes/Parse.Query.html#methods_select) feature:
  
  ```clj
  (defn parse-read [{:keys [ast]} key _]
    (let [{:keys [children]} ast]
      (cond
        (= \"class\" (namespace key))
        {:value (go
                  (->> (fp/query (name key) [[:select (map :dispatch-key children)]]) <!
                       (into [] (map #(parse-object % children)))))})))
  ```
  ")

(defcard query-runner-parse-3
  "Try again and check the network result"
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:class/Movie [:title]}]"
                                :parser (om/parser {:read sp/parse-read-3})}))))

(defcard-doc
  "
  This time we have:
  
  ![network-opt1](https://www.dropbox.com/s/da5trzt40vaefap/Screenshot%202015-12-17%2010.52.38.png?raw=1)
  
  It seems that Parse infers that we always need `createdAt` and `updateAt`, but what
  matters is that this way we are avoiding possibly long fields that we don't want.
  
  Since those fields would always be present I think to make sense exposing then, we
  can do that at the `parse-object` function:
  
  ```clj
  (defn parse-object [object children]
    (letfn [(compute-attribute [m {:keys [dispatch-key] :as attr}]
              (assoc m dispatch-key (fp/obj-get object (name dispatch-key))))]
      (-> (reduce compute-attribute {} children)
          (assoc :db/id         (gobj/get object \"id\")
                 :db/created-at (gobj/get object \"createdAt\")
                 :db/updated-at (gobj/get object \"updatedAt\")
                 :parse/class   (gobj/get object \"className\")))))
  ```
  
  Besides the fields we talked, there is the `:parse/class` field that will have the
  current class name, this will be useful to create ident's based on their original class.
  ")

(defcard query-runner-parse-4
  "Run the query again to see the new extra information:"
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:class/Movie [:title]}]"
                                :parser (om/parser {:read sp/parse-read-4})}))))

(defcard-doc
  "
  ### Reading pointers
  
  On Parse fields can be pointers, they are like foreign ids on databases, the difference
  here is that Parse provides facilities to reading those as we will see.
  
  Try to read the `:director` using our most recent parser
  
  ")

(defcard query-runner-parse-4-1
  "Run the query again to see the new extra information:"
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:class/Movie [:title :director]}]"
                                :parser (om/parser {:read sp/parse-read-4})}))))

(defcard-doc
  "
  We saw this before; we need to `parse-object` those.
  
  ```clj
  (defn parse-object-attribute [object {:keys [dispatch-key]}]
    (let [value (fp/obj-get object (name dispatch-key))]
      (if (aget value \"className\")
        (parse-object value nil)
        value)))
  
  (defn parse-object [object children]
    (letfn [(compute-attribute [m {:keys [dispatch-key] :as ast}]
              (assoc m dispatch-key (parse-object-attribute object ast)))]
      (-> (reduce compute-attribute {} children)
          (assoc :db/id         (gobj/get object \"id\")
                 :db/created-at (gobj/get object \"createdAt\")
                 :db/updated-at (gobj/get object \"updatedAt\")
                 :parse/class   (gobj/get object \"className\")))))
  ```
  
  We extracted the code from reading an object attribute into the `parse-object-attribute`
  function. To detect if a value is a pointer we check if it has a `className` if it does,
  it means it's a pointer so we can recursively call the `parse-object`.
  ")

(defcard query-runner-parse-5
  "
  Run to get with some basic director information.
  
  Also try running to get the director name as: `[{:class/Movie [:title {:director [:name]}]}]`
  "
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:class/Movie [:title :director]}]"
                                :parser (om/parser {:read sp/parse-read-5})}))))

(defcard-doc
  "
  You may notice that querying for the director name made no difference on the results.
  That's because we are sending `nil` to the `parse-object` where it's expecting the children
  AST. Can you tell from where we can get this child AST now? Let's revisit the parser
  again and see what the AST looks like in this case:
  ")

(defcard parser-read-trace
  sp/parse-inspector
  {:v "[{:class/Movie [:title {:director [:name]}]}]"})

(defcard-doc
  "
  Note that the node for `:director` is just like our root `:class/Movie`, a join where
  the children are the AST for the fields, can you feel the recursiveness of it?!
  
  ```clj
  (defn parse-object-attribute [object {:keys [dispatch-key children]}]
    (let [value (fp/obj-get object (name dispatch-key))]
      (if (aget value \"className\")
        (parse-object value children)
        value)))  
  ```
  ")

(defcard query-runner-parse-6
  "
  See the results of our recursion:
  "
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:class/Movie [:title {:director [:name]}]}]"
                                :parser (om/parser {:read sp/parse-read-6})}))))

(defcard-doc
  "
  We got empty values now; if you recall our network response, you may remember that
  the pointer value only had the `id` and `type`, not the actual data. Parse provides
  a facility here, at query level you can ask for `includes`, and those will be eager
  loaded without the main object, we can do it at our `parse-read` level:
  
  ```clj
  (defn extract-includes [children]
    (into [] (comp (filter #(= (:type %) :join))
                   (map #(-> [:include (-> % :key name)])))
          children))
  
  (defn parse-read [{:keys [ast]} key _]
    (let [{:keys [children]} ast]
      (cond
        (= \"class\" (namespace key))
        {:value (go
                  (->> (fp/query (name key) (concat [[:select (map :dispatch-key children)]]
                                                    (extract-includes children))) <!
                       (into [] (map #(parse-object % children)))))})))
  ```
  
  This time, we leverage the fact that every time we find a join we can expect that to
  be a nested resource to include, we are safe also because Parse will happily ignore
  invalid entries for the include.
  ")

(defcard query-runner-parse-7
  "
  Querying with includes:
  "
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:class/Movie [:title {:director [:name]}]}]"
                                :parser (om/parser {:read sp/parse-read-7})}))))

(defcard-doc
  "
  ### Recursive async calls
  
  Until this point we have being able to do a single remote call and parse the rest with
  sync code, that's about to change.
  
  This time, we want to answer the question \"what movies are directed by DIRECTOR\".
  
  On our database case, it means a one to many join, we did the `movie -> director` on the
  previous step, now let's go `director -> movies`.
  
  We have to define some way to express this relation on our parser, one way that I picked
  is with the following pattern: `:ClassName/_joinField`, so on our case from the director
  we can navigate to the movies by `:Movie/_director`.
  
  ```clj
  (defn parse-object-attribute [object {:keys [dispatch-key children]}]
    (let [value (fp/obj-get object (name dispatch-key))]
      (cond
        (some-> value (aget \"className\"))
        (parse-object value children)
  
        (and (not (nil? (namespace dispatch-key)))
             (gstr/startsWith (name dispatch-key) \"_\"))
        (let [class (namespace dispatch-key)
              field (.substr (name dispatch-key) 1)]
          (fp/query class [[:equal-to field object]]))
        
        :else value)))
  ```
  ")

(defcard query-runner-parse-8
  "
  Try reading the join:
  "
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:class/Person [:name :Movie/_director]}]"
                                :parser (om/parser {:read sp/parse-read-8})}))))

(defcard-doc
  "
  The `fp/query` bit seems very familiar doesn't it? We are going to need the same thing
  in both places, let's extract it out:
  
  ```clj
  (defn query-class [class query children]
    (go
      (->> (fp/query class (concat [[:select (map :dispatch-key children)]]
                                   (extract-includes children)
                                   query)) <!
           (into [] (map #(parse-object % children))))))
  
  (defn parse-object-attribute [object {:keys [dispatch-key children]}]
    (let [value (fp/obj-get object (name dispatch-key))]
      (cond
        (some-> value (aget \"className\"))
        (parse-object value children)
  
        (and (not (nil? (namespace dispatch-key)))
             (gstr/startsWith (name dispatch-key) \"_\"))
        (let [class (namespace dispatch-key)
              field (.substr (name dispatch-key) 1)]
          (query-class class [[:equal-to field object]] children))
  
        :else value)))
  
  (defn parse-object [object children]
    (letfn [(compute-attribute [m {:keys [dispatch-key] :as ast}]
              (assoc m dispatch-key (parse-object-attribute object ast)))]
      (-> (reduce compute-attribute {} children)
          (assoc :db/id         (gobj/get object \"id\")
                 :db/created-at (gobj/get object \"createdAt\")
                 :db/updated-at (gobj/get object \"updatedAt\")
                 :parse/class   (gobj/get object \"className\")))))
  
  (defn parse-read [{:keys [ast]} key _]
    (let [{:keys [children]} ast]
      (cond
        (= \"class\" (namespace key))
        {:value (query-class (name key) [] children)})))
  ```
  ")

(defcard query-runner-parse-9
  "
  The code got cleaner, and now we have a single entry point to read lists that can
  work recursively, but we still have the channel being returned as a problem, you can
  test again:
  "
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:class/Person [:name :Movie/_director]}]"
                                :parser (om/parser {:read sp/parse-read-9})}))))

(defcard-doc
  "
  At this point we are back on our first async issue of this tutorial, a map that may
  contain channels as values, any attribute read can potentially return one of those so
  makes sense to generalize that at `parse-object`:
  
  ```clj
  (defn parse-object [object children]
    (letfn [(compute-attribute [m {:keys [dispatch-key] :as ast}]
              (assoc m dispatch-key (parse-object-attribute object ast)))]
              
      (-> (reduce compute-attribute {} children)
          (assoc :db/id         (gobj/get object \"id\")
                 :db/created-at (gobj/get object \"createdAt\")
                 :db/updated-at (gobj/get object \"updatedAt\")
                 :parse/class   (gobj/get object \"className\"))
          (read-chan-values))))
  ```
  ")

(defcard query-runner-parse-10
  "
  "
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:class/Person [:name :Movie/_director]}]"
                                :parser (om/parser {:read sp/parse-read-10})}))))

(defcard-doc
  "
  Now we have a list of channels as a response to one `query-class`, this time, we have
  to imagine that this list can be quite big, will be nice to process some of those in
  paralel, luckly for us `core.async` makes it easy:
  
  ```clj
  (defn query-class [class query children]
    (go
      (let [res (<! (fp/query class (concat [[:select (map :dispatch-key children)]]
                                            (extract-includes children)
                                            query)))
            out (async/chan 64)]
        (async/pipeline-async 10 out (fn [x c] (go
                                                 (>! c (<! (parse-object x children)))
                                                 (close! c)))
                              (async/to-chan res))
        (<! (async/into [] out)))))

  ```
  ")

(defcard query-runner-parse-11
  "
  Finally fellows, our recursive async parser in action.
  
  Other queries for you to try:
  
  * `[{:class/Person [:name {:Movie/_director [:title]}]}]`
  * `[{:class/Movie [:title {:director [:name {:Movie/_director [:title]}]}]}]\n`
  "
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:class/Person [:name :Movie/_director]}]"
                                :parser (om/parser {:read sp/parse-read-11})}))))

(defcard-doc
  "
  ### Dispatching on specific models
  
  Now that the async parser bits are in place adding new stuff gets easier. One cool
  feature that I want to you to see is one way to have reads targeted at specific Parse
  models. Multimethods are a nice fit here:
  
  ```clj
  (defmulti read-parse-class (fn [object key] [(gobj/get object \"className\") key]))
  
  (defmethod read-parse-class :default
    [_ _] nil)
  
  (defmethod read-parse-class [\"Person\" :directed-count]
    [object _]
    (fp/count \"Movie\" [[:equal-to \"director\" object]]))
     
  (defn parse-object-attribute [object {:keys [dispatch-key children]}]
    (let [value (fp/obj-get object (name dispatch-key))]
      (cond
        (some-> value (aget \"className\"))
        (parse-object value children)
  
        (and (not (nil? (namespace dispatch-key)))
             (gstr/startsWith (name dispatch-key) \"_\"))
        (let [class (namespace dispatch-key)
              field (.substr (name dispatch-key) 1)]
          (query-class class [[:equal-to field object]] children))
  
        :else (or (read-parse-class object dispatch-key) value))))
  ```
  
  Our multimethod get's a Parse object and extracts its class name, plus the name of the
  method. Next we created a definition for the `Person` class to return the count of
  movies that were directed by that person, note we returned a `core.async` channel, we
  can return values or channels and it will be handled automatically, pretty convenient.
  ")

(defcard query-runner-parse-12
  "
  Test the `:directed-count` accessor:
  "
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:class/Person [:name :directed-count]}]"
                                :parser (om/parser {:read sp/parse-read-12})}))))

(defcard-doc
  "
  At this point adding custom entry points is also trivial, let's say we want an endpoint
  for `:app/recent-movies` that will get the movies of this year, we can implement as:
  
  ```clj
  (defn start-of-year []
    (let [date (js/Date.)]
      (js/Date. (str (.getFullYear date) \"-01-01T00:00:00.000Z\"))))
  
  (defn parse-read [{:keys [ast]} key _]
    (let [{:keys [children]} ast]
      (case key
        :app/recent-movies
        {:value (query-class \"Movie\" [[:greater-than \"releaseDate\" (start-of-year)]]
                                children)}
        
        (cond
          (= \"class\" (namespace key))
          {:value (query-class (name key) [] children)}))))
  ```
  ")

(defcard query-runner-parse-13
  "
  Test our new entry point:
  "
  (fn [_ _]
    (dom/div nil
      (sp/remote-query-console {:value  "[{:app/recent-movies [:title {:director [:name]}]}]"
                                :parser (om/parser {:read sp/parse-read-13})}))))

(defcard-doc
  "
  ## Integrating on the reconciler
  
  Time to test our parser against a UI, this code demonstrates how you can setup the
  reconciler with a basic local reader to use our remote async reader:
  
  ```clj
  (defn parse-ident [{:keys [parse/class db/id]}]
    [(keyword class \"by-id\") id])
  
  (defui Director
    om/Ident
    (ident [_ props] (parse-ident props))
    
    om/IQuery
    (query [_] [:db/id :parse/class :name])
    
    Object
    (render [this]
      (let [{:keys [name]} (om/props this)]
        (dom/div nil
          \"Director: \" name))))
  
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
          \"Movie \" title
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
  ```
  ")

(defcard sample-app
  "
  Due to a current bug on the Om.next/devcards integration you have to click on the
  title of this card to force it to render the current loaded data."
  (om/mock-root sp/reconciler sp/MovieApp))

(defcard-doc
  "
  ## Conclusion
  
  Hope you enjoyed this tutorial, the intent was to demonstrate one way to implement parser
  and guide you though how to use the AST on the parsing process. Use this sources and
  ajust for your needs, remember that you are the one who defines your parser semantics.
  
  ## Things not done
  
  Here are a few exercicies that you can try to do to improve this parser:
  
  * Error handling: for this tutorial we just assumed errors don't happen, but in reality
  they do, the parse async calls can return an error object, you can detect this is propate
  some error message back.
  * Support pagination: use the query parameters to support pagition, you can use keys
  like `:offset` and `:limit` on a query like `[({:class/Movie [:title]} {:limit 2})]`
  * Deep includes: our include optimization can only reach for the first level of children,
  but reading the AST you can detect it deeper, Parse.com supports deep children eager
  loading by including things like `movie.genre`.
  * Include all element fields: implement the query like `[{:class/Movie [*]}]` to read
  all entity attributes
  * Support more join types: Parse also provides array types and relational types that
  can be implemented.
  ")
