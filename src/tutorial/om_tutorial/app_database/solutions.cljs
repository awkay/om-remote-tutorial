(ns om-tutorial.app-database.solutions)

;; ANS (exercise 1): Add a :cars/by-id table
(def cars-table {:cars/by-id {1 {:id 1 :make "Nissan" :model "Leaf"}
                              2 {:id 2 :make "Dodge" :model "Dart"}
                              3 {:id 3 :make "Ford" :model "Mustang"}}})

;; ANS (exercise 2): merge your cars table from above here
;; ANS (exercise 2): Add a :favorite-car key that points to the Nissan Leaf via an ident
(def favorites (merge cars-table {:favorite-car [:cars/by-id 1]}))

;; ANS (exercise 3): Add tables. See exercise text.
(def ex3-uidb {:tools/by-id {1 {:id 1 :label "Cut"}
                             2 {:id 2 :label "Copy"}}
               :data/by-id  {1 {:id 5 :x 1 :y 3}}
               :toolbar     {:main {:tools [[:tools/by-id 1] [:tools/by-id 2]]}}
               :canvas      {:main {:data [[:data/by-id 1]]}}
               :main-panel  {:toolbar [:toolbar :main]
                             :canvas  [:canvas :main]}})
