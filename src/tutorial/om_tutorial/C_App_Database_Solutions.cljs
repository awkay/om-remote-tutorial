(ns om-tutorial.C-App-Database-Solutions
  (:require-macros [cljs.test :refer [is]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [om-tutorial.app-database.solutions :refer [cars-table favorites ex3-uidb]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc deftest]]
            [cljs.reader :as r]))

(defcard-doc
    "
    # App Database Exercises

    Open the namespace `om-tutorial.app-database.exercises`. There are TODO
    placeholders for each exercise.

    When you have completed the task described by an exercise, the tests
    for those exercises will turn green.")

(dc/deftest exercise-1
   "In this exercise, you'll verify that you understand how to create a
   database table in the default Om database format.

   Create a table named `:cars/by-id` with the following items:

   ```
   { :id 1 :make \"Nissan\" :model \"Leaf\" }
   { :id 2 :make \"Dodge\" :model \"Dart\" }
   { :id 3 :make \"Ford\" :model \"Mustang\" }
   ```

   These tests shown below will pass when the table is
   correctly formatted.
   "

   (is (= "Nissan" (-> cars-table (get-in [:cars/by-id 1]) :make)))
   (is (= "Dodge" (-> cars-table (get-in [:cars/by-id 2]) :make)))
   (is (= "Ford" (-> cars-table (get-in [:cars/by-id 3]) :make))))

(dc/deftest exercise-2
   "In this exercise, you'll use Idents to link together data
   in an app database.

   Merge the cars table into provided favorites database, then
   add a `:favorite-car` key that uses an ident to reference
   the Nissan Leaf.

   "
   (is (= "Nissan" (->> (get favorites :favorite-car) (get-in favorites) (:make)))))

(dc/deftest exercise-3
   "This exercise has you build up more of a UI, but all as normalized
   components.

   Say you want to have the following UI data:

   ```
   { :main-panel { :toolbar { :tools [ {:id 1 :label \"Cut\"} {:id 2 :label \"Copy\"} ]}
                   :canvas  { :data [ {:id 5 :x 1 :y 3} ]}}}
   ```

   but you want to normalize tool instances using `:tools/by-id`, data via `:data/by-id`.
   Use the invented idents `[:toolbar :main]` and `[:canvas :main]` to move the toolbar and canvas to
   tables of their own.

   Build the normalized database in `ex3-uidb`.

   The following tests will pass when you get the format correct.
   "
   (is (= {:main-panel
           {:toolbar {:tools [{:label "Cut"} {:label "Copy"}]},
            :canvas  {:data [{:x 1, :y 3}]}}}
          (om/db->tree '[{:main-panel [{:toolbar [{:tools [:label]}]}
                                       {:canvas [{:data [:x :y]}]}]}] ex3-uidb ex3-uidb))))

(defcard-doc
  "[Next: Queries](#!/om_tutorial.D_Queries)")

