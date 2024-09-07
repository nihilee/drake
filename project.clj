(defproject factual/drake "1.0.5"
  :description "Drake: the data processing workflow tool (a.k.a. 'make for data')"
  :url "https://github.com/Factual/drake"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/Factual/drake"}
  :deploy-repositories [["clojars" {:creds :gpg}]]
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/core.memoize "1.1.266"]
                 [factual/drake-interface "0.0.1"]
                 [org.clojure/tools.logging "1.3.0"]
                 [clj-logging-config "1.9.12"]  ;; This library depends on log4j 1.2 which is end-of-line and has known security vulnerabilities. Please do not use.
                 [clojopts/clojopts "0.3.5"]
                 [org.flatland/useful "0.11.6"]
                 [clj-commons/fs "1.6.307"]
                 [org.clj-commons/digest "1.4.100"]
                 [com.google.guava/guava "33.3.0-jre"]
                 [cheshire "5.13.0"]
                 [rhizome "0.2.9"]
                 [slingshot "0.12.2"]
                 [factual/fnparse "2.3.0"]  ;; TODO
                 [commons-codec/commons-codec "1.17.1"]
                 [factual/sosueme "0.0.16"]
                 [factual/c4 "0.2.1"]
                ;;  [hdfs-clj "0.1.3"]    ;; for HDFS support
                ;;  [org.apache.hadoop/hadoop-core "0.20.2"]
                ;;  [clj-aws-s3 "0.3.10" :exclusions [joda-time]]    ;; for AWS S3 support
                 [org.jgrapht/jgrapht-core "1.4.0"]
                 ;; for plugins
                 [com.cemerick/pomegranate "0.3.1"]]
  :test-selectors {:regression   :regression
                   :default      (complement :regression)
                   :all          (constantly true)}
  :main drake.core
  :uberjar-name "drake.jar"
  :aot :all)
