(defproject common-crawler "0.1.0-SNAPSHOT"
  :description "Common Crawler job search tool"
  :url "http://example.com/job-crawler"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}

  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.jwat/jwat-warc "1.1.1"]
                 [clj-http "3.12.3"]
                 [org.clojure/data.json "2.4.0"]
                 [enlive "1.1.6"]
                 [cheshire "5.12.0"]
                 ;; Additional dependencies for logging and configuration
                 [ch.qos.logback/logback-classic "1.4.14"]
                 [org.slf4j/jcl-over-slf4j "2.0.11"]]

  :main common-crawler.core
  :target-path "target/%s"

  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:dependencies [[criterium "0.4.6"]  ; for benchmarking
                                  [pjstadig/humane-test-output "0.11.0"]]
                   :plugins [[lein-kibit "0.1.8"]      ; static code analyzer
                             [jonase/eastwood "1.4.0"]]  ; linter
                   :source-paths ["dev"]
                   :repl-options {:init-ns common-crawler.core}}}

  :jvm-opts ["-Xmx4g"  ; Increase heap size for processing large WARC files
             "-server"
             "-Dfile.encoding=UTF-8"]

  ;; Exclude conflicting dependencies
  :exclusions [commons-logging
               org.slf4j/slf4j-log4j12
               log4j]

  ;; Additional configuration for building executable jar
  :uberjar-name "job-crawler-standalone.jar"

  ;; Repository for archive.org dependencies
  :repositories {"internetarchive"
                 {:url "https://builds.archive.org/maven2"
                  :snapshots false}})
