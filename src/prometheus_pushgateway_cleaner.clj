(ns prometheus-pushgateway-cleaner
  (:require [clojure.string :as str]
            [clojure.pprint :refer [print-table]]
            [clj-http.lite.client :as http]
            [clj-http.lite.util :as http-util]
            [clojure.tools.cli :as cli]
            [clojure.instant :as instant]
            [hickory.core :as hickory])
  (:import (java.net URI))
  (:gen-class))

(set! *warn-on-reflection* true)


(def version "0.0.1")

(def usage "hi")

(defn parse-url [s]
  (URI. (if (= (last s) \/)
          s
          (str s \/))))

(defn min->ms [m]
  (* m 1000 60))

(defn ms->s [ms]
  (/ ms 1000))


(def cli-options
  [[:long-opt "--web-url"
    :required "WEB_URI"
    :desc "URI of the web interface to crawl"
    :parse-fn parse-url
    :validate [some? "Most be set"]]
   [:long-opt "--job-url"
    :required "JOB_URI"
    :desc "URI to update metrics"
    :parse-fn parse-url
    :default-fn (fn [{:keys [^URI web-url]}]
                  (when web-url
                    (.resolve web-url "metrics/job/")))
    :default-desc "WEB_URI + /metrics/job"]
   [:long-opt "--expiration-in-minutes"
    :required "DURATION"
    :desc "Jobs not updated longer than the specified time will be deleted"
    :default 60
    :parse-fn #(Integer/parseInt %)
    :validate [pos-int? "Most be a positive integer"]]
   [:long-opt "--basic-auth"
    :required "BASIC_AUTH"
    :desc "Request header(s)"
    :parse-fn #(str/split % #":")
    :validate [#(or (nil? %)
                    (not (or (str/blank? (first %))
                             (str/blank? (second %)))))
               "Must be a string like 'username:password'"]]
   [:long-opt "--interval-in-minutes"
    :required "INTERVAL"
    :desc "When set, process keeps running and repeats check after interval time"
    :parse-fn #(Integer/parseInt %)
    :validate [pos-int? "Most be a positive integer"]]
   [:long-opt "--dry-run"
    :desc "Log results but don't delete anything"]
   [:long-opt "--report-metrics"
    :desc "Push metric (see metric-name) to pushgateway which contains a unix timestamp (in s) of the last time cleaning finished sucessfully"]
   [:long-opt "--metric-name"
    :desc "Job name of the metric to push to pushgateway if --report-metrics is set"
    :default "prometheus_pushgateway_cleaner_last_success"]
   ["-s"
    "--silent"
    "Print nothing"]
   ["-v"
    "--version"
    "Print version"
    :id :print-version]
   ["-h"
    "--help"
    "Print this message"]])

(defn get-html-path [p el]
  (reduce (fn [res [q v]]
            (mapcat (fn [x] (filter #(str/includes? (str (get-in % q)) (name v)) (:content x))) res))
          [el]
          p))

(def path-to-cards [[[:tag] :html]
                    [[:tag] :body]
                    [[:attrs :id] "metrics-div"]
                    [[:attrs :id] "job-accordion"]
                    [[:attrs :class] "card"]])

(def path-to-ts [[[:attrs :class] "collapse"]
                 [[:attrs :class] "card-body"]
                 [[:attrs :class] "accordion"]
                 [[:attrs :class] "card"]
                 [[:attrs :class] "card-header"]
                 [[:tag] :h2]
                 [[:tag] :button]])

(def path-to-labels [[[:attrs :class] "card-header"]
                     [[:tag] :h2]
                     [[:attrs :class] "collapsed"]
                     [[:attrs :class] "badge"]])


(def ts-regex #"\s+last pushed:\s+(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z)\s+")

(defn get-cards-from-html [html]
  (get-html-path path-to-cards
                 (hickory/as-hickory (hickory/parse html))))

(defn get-last-ts-for-card [card]
  (apply max (map #(inst-ms (instant/read-instant-date
                              (second (re-find
                                        ts-regex
                                        (last (:content %))))))
                  (get-html-path path-to-ts card))))

(defn get-expired-cards [expiration-time cards]
  (filter #(< (get-last-ts-for-card %) expiration-time) cards))

(defn get-job-for-card [card]
  (->> (get-html-path path-to-labels card)
       (map :content)
       (map first)
       (map #(str/split % #"="))
       (map #(vector (first %) (str/replace (second %) #"\"" "")))
       (into {})))

(defn resolve-job-url [job-url job]
  (reduce
    (fn [^URI uri s]
      (.resolve uri (str (http-util/url-encode s) "/")))
    job-url
    (flatten (into [(get job "job")] (dissoc job "job")))))

(defn extract-expired-job-urls [{:keys [req expiration-time job-url log]}]
  (let [cards (get-cards-from-html (:body req))
        expired-cards (get-expired-cards expiration-time cards)
        expired-jobs (map get-job-for-card expired-cards)]
    (log (str "Found " (count cards) " jobs"))
    (log (str (count expired-cards) " expired jobs"))
    (map #(resolve-job-url job-url %)
         expired-jobs)))

(defn silent-logger [& args] nil)
(defn stdout-logger [& args] (apply println args))

(defn now-in-ms []
  (inst-ms (java.time.Instant/now)))

(defn push-metric [{:keys [job-url basic-auth metric-name now]}]
  (http/request {:url (.resolve job-url (http-util/url-encode metric-name))
                 :basic-auth basic-auth
                 :method :put
                 :body (str metric-name " " (ms->s now))}))

(defn run [{:keys [log
                   web-url
                   job-url
                   basic-auth
                   dry-run
                   expiration-in-minutes
                   report-metrics
                   metric-name]}]
  (when dry-run
    (log "Dry run: Won't delete any data"))
  (log "Fetching data from Prometheus pushgateway")
  (let [req (http/request {:url web-url
                           :basic-auth basic-auth
                           :method :get})
        now (now-in-ms)
        expiration-in-ms (min->ms expiration-in-minutes)
        expired-job-urls (extract-expired-job-urls {:req req
                                                    :expiration-time (- now expiration-in-ms)
                                                    :job-url job-url
                                                    :log log})]
    (doseq [url expired-job-urls]
      (if dry-run
        (log (str "(Dry run) Deletion of job: " url))
        (do
          (log (str "Deleting job: " url))
          (http/request {:url url
                         :basic-auth basic-auth
                         :method :delete}))))
    (log "Done")
    (when report-metrics
      (if dry-run
        (log "(Dry run) Pushing success metric")
        (do (log "Pushing success metric")
            (push-metric {:job-url job-url
                          :basic-auth basic-auth
                          :metric-name metric-name
                          :now (now-in-ms)})
            (log "Metric pushed"))))))

(defn run-in-interval [{:as options :keys [log interval-in-minutes]}]
  (log "Will run in interval")
  (loop []
    (log "Running..")
    (run options)
    (log (str "Will sleep for " interval-in-minutes " " (if (> interval-in-minutes 1)
                                                          "minutes"
                                                          "minute")))
    (Thread/sleep (min->ms interval-in-minutes))
    (recur)))


(defn -main
  "Handles args parsing and does the appropriate action."
  [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)
        {:keys [help
                web-url
                print-version
                silent
                interval-in-minutes]} options
        log (if silent
              silent-logger
              stdout-logger)
        run-options (assoc options
                           :log log)]
    (log "Welcome to Prometheus pushgateway cleaner")
    (log "Running with options:")
    (log (with-out-str
           (print-table (map (fn [[k v]] {"option" (name k)
                                          "value" v})
                             options))))
    (cond
      help          (do (log usage)
                        (log summary))
      print-version (log version)
      errors        (do (run! log errors)
                        (System/exit 1))
      (not web-url) (do (log "--web-url is required")
                        (System/exit 1))
      interval-in-minutes (run-in-interval (assoc run-options
                                                  :interval-in-minutes interval-in-minutes))
      :else         (run run-options))))

; TODO usage text
; TODO readme
; TODO use hickory selectors

