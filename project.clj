(defproject om-tutorial "0.1.0-SNAPSHOT"
  :description "A Tutorial for Om 1.0.0 (next)"
  :dependencies [[org.clojure/clojure "1.9.0-alpha14" :scope "provided"]
                 [org.clojure/clojurescript "1.9.456" :scope "provided"]
                 [devcards "0.2.2" :exclusions [org.omcljs/om cljsjs/react-dom org.clojure/tools.reader cljsjs/react]]
                 [datascript "0.15.5"]
                 [org.omcljs/om "1.0.0-alpha47"]
                 [figwheel-sidecar "0.5.9" :exclusions [clj-time joda-time org.clojure/tools.reader] :scope "test"]
                 [cljsjs/codemirror "5.21.0-2"]]

  :source-paths ["src/main" "src/cards" "src/tutorial"]

  :plugins [[lein-cljsbuild "1.1.5"]
            [lein-ancient "0.6.10"]]

  :clean-targets ^{:protect false} ["resources/public/js" "resources/public/cards" "resources/public/tutorial" "target"]

  :figwheel {:build-ids   ["dev" "cards" "tutorial"]
             :server-port 3450}

  :cljsbuild {
              :builds
              [
               {:id           "dev"
                :figwheel     true
                :source-paths ["src/main"]
                :compiler     {:main                 om-tutorial.core
                               :asset-path           "js"
                               :output-to            "resources/public/js/main.js"
                               :output-dir           "resources/public/js"
                               :recompile-dependents true
                               :parallel-build       true
                               :verbose              false}}
               {:id           "cards"
                :figwheel     {:devcards true}
                :source-paths ["src/main" "src/cards"]
                :compiler     {
                               :main                 om-tutorial.cards
                               :source-map-timestamp true
                               :asset-path           "cards"
                               :output-to            "resources/public/cards/cards.js"
                               :output-dir           "resources/public/cards"
                               :recompile-dependents true
                               :parallel-build       true
                               :verbose              false}}
               {:id           "tutorial"
                :figwheel     {:devcards true}
                :source-paths ["src/main" "src/tutorial"]
                :compiler     {
                               :main                 om-tutorial.tutorial
                               :source-map-timestamp true
                               :asset-path           "tutorial"
                               :output-to            "resources/public/tutorial/tutorial.js"
                               :output-dir           "resources/public/tutorial"
                               :parallel-build       true
                               :recompile-dependents true
                               :verbose              false
                               :foreign-libs         [{:provides ["cljsjs.codemirror.addons.closebrackets"]
                                                       :requires ["cljsjs.codemirror"]
                                                       :file     "resources/public/codemirror/closebrackets-min.js"}
                                                      {:provides ["cljsjs.codemirror.addons.matchbrackets"]
                                                       :requires ["cljsjs.codemirror"]
                                                       :file     "resources/public/codemirror/matchbrackets-min.js"}]}}
               {:id           "pages"
                :source-paths ["src/main" "src/tutorial" "src/prod"]
                :compiler     {
                               :main                 core
                               :devcards             true
                               :asset-path           "pages"
                               :output-to            "resources/public/pages/tutorial.js"
                               :output-dir           "resources/public/pages"
                               :parallel-build       false
                               :verbose              true
                               :optimizations        :advanced
                               :foreign-libs         [{:provides ["cljsjs.codemirror.addons.closebrackets"]
                                                       :requires ["cljsjs.codemirror"]
                                                       :file     "resources/public/codemirror/closebrackets-min.js"}
                                                      {:provides ["cljsjs.codemirror.addons.matchbrackets"]
                                                       :requires ["cljsjs.codemirror"]
                                                       :file     "resources/public/codemirror/matchbrackets-min.js"}]}}]}

  :profiles {
             :dev {:source-paths ["src/dev" "src/main" "src/tutorial"]
                   :dependencies [[com.cemerick/piggieback "0.2.1"]
                                  [org.clojure/tools.nrepl "0.2.12"]]
                   :repl-options {:init-ns user
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]
                                  :port    7001}}})



