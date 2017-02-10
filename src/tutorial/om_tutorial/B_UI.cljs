(ns om-tutorial.B-UI
  (:require-macros
    [cljs.test :refer [is]])

  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))
;
;
(defui Widget
  Object
  (render [this]
    (dom/div nil "Hello world")))

(defui WidgetWithHook
  Object
  (componentWillUpdate [this nextprops nextstate] (println "Component will update"))
  (render [this]
    (dom/div nil "Hello world")))

(def widget (om/factory Widget))

(defui WidgetWithProperties
  Object
  (render [this]
    (let [{:keys [name]} (om/props this)]
      (dom/div nil (str "Hello " name)))))

(def prop-widget (om/factory WidgetWithProperties))


(defcard-doc
  "
  # UI

  Om uses <a href=\"https://facebook.github.io/react/index.html\" target=\"_blank\">React</a> underneath.
  The primary mechanism for creating components is the `defui` macro:"
  (dc/mkdn-pprint-source Widget)
  "This macro generates a React Class as a plain javascript class, so it is completely compatible with the
  React ecosystem.

  Notice the use of `Object`. It indicates that the following list of method bodies (like in protocols) are being
  added to the general class. From an OO perspective, this is like saying \"my widget extends Object\". The
  `render` method is the only method you need, but you can also add in your own methods or React lifecycle methods.

  ## React Lifecycle Methods

  If you wish to provide <a href=\"https://facebook.github.io/react/docs/component-specs.html#lifecycle-methods\"
  target=\"_blank\">lifecycle methods</a>, you can define them under the Object section of the UI:
  "
  (dc/mkdn-pprint-source WidgetWithHook)
  "
  ## Element factory

  In order to render components on the screen you need an element factory.
  You generate a factory with `om/factory`, which will then
  act like a new 'tag' for your DOM:"
  (dc/mkdn-pprint-source widget)
  "Since they are plain React components you can render them in a <a href=\"https://github.com/bhauman/devcards#devcards\"
  target=\"_blank\">devcard</a>, which makes fine tuning them as pure UI dead simple:

  ```
  (defcard widget-card (widget {}))
  ```
  "
  "The resulting card looks like this:")


(defcard widget-card (widget {}))

(defcard-doc
  "Such components are known as \"stateless components\" in Om because they do not explicitly ask for data. Later,
  when we learn about colocated queries, you'll see it is possible for a component to ask for the data it needs in
  a declarative fashion.

  For now, understand that you can give data to a stateless component via a simple edn map, and pull them out of
  `this` using `om/props`:"
  (dc/mkdn-pprint-source WidgetWithProperties)
  (dc/mkdn-pprint-source prop-widget)
  "
  ```
  (defcard props-card (prop-widget {:name \"Sam\"}))
  ```
  ")


(defcard props-card (prop-widget {:name "Sam"}))

(defui Person
  Object
  (render [this]
    (let [{:keys [name]} (om/props this)]
      (dom/li nil name))))

(def person (om/factory Person {:keyfn :name}))

(defui PeopleList
  Object
  (render [this]
    (let [people (om/props this)]
      (dom/ul nil (map person people)))))

(def people-list (om/factory PeopleList))

(defui Root
  Object
  (render [this]
    (let [{:keys [people number]} (om/props this)]
      (dom/div nil
        (dom/span nil (str "My lucky number is " number " and I have the following friends:"))))))
        ;(people-list people)))))

(def root (om/factory Root))

#_(defcard-doc)
  "
  ## Composing the UI

  Composing these is pretty straightforward: pull out the bits from props, and pass them on to subcomponents.
  "
  (dc/mkdn-pprint-source Person)
  (dc/mkdn-pprint-source PeopleList)
  (dc/mkdn-pprint-source Root)
  (dc/mkdn-pprint-source people-list)
  (dc/mkdn-pprint-source person)
  (dc/mkdn-pprint-source root)
  "

  ```
  (defcard root-render (root {:number 52 :people [{:name \"Sam\"} {:name \"Joe\"}]}))
  ```

  It is important to note that _this is exactly how the composition of UI Components always happens_, independent of
  whether or not you use the rest of the features of Om. A root component calls the factory functions of subcomponents
  with an edn map as the first argument. That map is accessed using `om/props` on `this` within the subcomponent. Data
  is passed from component to component through `props`.

  You might notice something new here: the `om/factory` function is supplied with an additional map `{:keyfn :name}`.
  The factory function can be optionally supplied with two keywords: `:keyfn` and `:validator`. `:keyfn` produces the
  <a href=\"https://facebook.github.io/react/docs/multiple-components.html\" target=\"_blank\">React key property</a>
  from component props (here it's `:name`), while `:validator`takes a function that asserts the validity of the props received.

  ## Play With It

  At this point (if you have not already) you should play with the code in `B-UI.cljs`. Search for `root-render`
  and then scan backwards to the source. You should try adding an object to the properties (another person),
  and also try playing with editing/adding to the DOM.
  "

(defcard root-render (root {:number 52 :people [{:name "Sam"} {:name "Joe"}]}))

(defui Root-computed
  Object
  (render [this]
    (let [{:keys [people number b]} (om/props this)
          {:keys [incHandler boolHandler]} (om/get-computed this)]
      (dom/div nil
        ; code pprinter cannot deal with #js on rendering source. Using clj->js instead
        (dom/button (clj->js {:onClick #(boolHandler)}) "Toggle Luck")
        (dom/button (clj->js {:onClick #(incHandler)}) "Increment Number")
        (dom/span nil (str "My " (if b "" "un") "lucky number is " number
                        " and I have the following friends:"))
        (people-list people)))))

(def root-computed (om/factory Root-computed))

(defcard-doc
  "
  ## Out-of-band Data: Callbacks and such

  In plain React, you store component local state and pass stuff from the parent through props.
  Om is no different, though component-local state is a matter of much debate since you get many advantages from
  having a stateless UI. In React, you also pass your callbacks through props. In Om, we need a slight variation of
  this.

  In Om, a component can have a query that asks the underlying system for data. If you complect callbacks and such
  with this queried data then you run into trouble. So, in general *props have to do with passing data that
  the component requested from a query*.

  As such, Om has an additional mechanism for passing things that were not specifically asked for in a query: Computed
  properties.

  For your Om UI to function properly you must attach computed properties *to* props via the helper function `om/computed`.
  The child can look for these computed properties using `get-computed`.

  (remember about the use of `clj->js`...devcards can't currently render the source of something with reader tags in it.
  You'd normally write `#js { :onClick ...}`).

  "

  (dc/mkdn-pprint-source Root-computed)
  (dc/mkdn-pprint-source root-computed)

  "
  ## Play with It!

  Open B-UI.cljs, search for `passing-callbacks-via-computed`, and you'll find the card shown below. Interact with it
  in your browser, play with the source, and make sure you understand everything we've covered so far.
  ")

(defcard passing-callbacks-via-computed
  (fn [data-atom-from-devcards _]
    (let [prop-data @data-atom-from-devcards
          sideband-data {:incHandler  (fn [] (swap! data-atom-from-devcards update-in [:number] inc))
                         :boolHandler (fn [] (swap! data-atom-from-devcards update-in [:b] not))}]

      (root-computed (om/computed prop-data sideband-data))))
  {:number 42 :people [{:name "Sally"}] :b false}
  {:inspect-data true
   :history      true})

(defui Styled-component
  Object
  (render [this]
    (let [style (clj->js {:color "red"})]
      (dom/div (clj->js {:style style}) "This text is red!"))))

(def styled-component (om/factory Styled-component))

(defcard-doc
  "
    ##Styling DOM elements

    Styling can be applied to DOM elements through the JavaScript map passed to
    them as second parameter.
    The styling has to be a JavaScript map."

  (dc/mkdn-pprint-source Styled-component))

(defcard styling-example
         (styled-component))


(defcard-doc
  "

  ## Important Notes and Further Reading

  - Remember to use `#js` (shown as `clj->js` in many examples) to transform attribute maps for passing to DOM elements
  - Use *cljs* maps as input to your own Elements
  - Extract properties with `om/props`. This is the same for stateful (with queries) or stateless components.
  - Add parent-generated things (like callbacks) using `om/computed`.

  TODO: Add links to various docs

  [Now let's try some exercises.](#!/om_tutorial.B_UI_Exercises)
  ")
