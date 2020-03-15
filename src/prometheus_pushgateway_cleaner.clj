(ns prometheus-pushgateway-cleaner
  (:require [clojure.string :as str]
            [clojure.pprint :refer [print-table]]
            [clj-http.lite.client :as http]
            [clj-http.lite.util :as http-util]
            [clojure.tools.cli :as cli])
  (:import (java.net URI))
  (:gen-class))

(set! *warn-on-reflection* true)


(def version "0.0.1")

(def intro "
> Delete old metric timeseries from Prometheus pushgateway

When you need this tool, you are most likely using pushgateway in an unindented way,
so make sure you really want to be doing this :)
")

(defn parse-url [s]
  (URI. (if (= (last s) \/)
          s
          (str s \/))))

(defn min->ms [m]
  (* m 1000 60))

(defn s->ms [s]
  (* s 1000))

(defn ms->s [ms]
  (/ ms 1000))


(def cli-options
  [[:long-opt "--metric-url"
    :required "METRIC_URL"
    :desc "(REQUIRED) URI of the metric endpoint to crawl. Probably ends with /metrics/"
    :parse-fn parse-url
    :validate [some? "Most be set"]]
   [:long-opt "--job-url"
    :required "JOB_URI"
    :desc "URI to update metrics"
    :parse-fn parse-url
    :default-fn (fn [{:keys [^URI metric-url]}]
                  (when metric-url
                    (.resolve metric-url "job/")))
    :default-desc "METRIC_URL + /job/"]
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
    :desc "Push metric (see success-metric) to pushgateway which contains a unix timestamp (in s) of the last time cleaning finished sucessfully"]
   [:long-opt "--success-metric"
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


(defn parse-line [line]
  (let [[_ lstr v] (re-matches #"^push_time_seconds\{(.+)\} (.+)" line)
        all-labels (into {} (filter some? (map #(let [[a b] (str/split % #"=")
                                                      lv (second (re-matches #"\"(.+)\"" b))] (when lv [a lv])) (str/split lstr #","))))]
    {:value (s->ms (Double/parseDouble v))
     :job (get all-labels "job")
     :labels (dissoc all-labels "job")}))

(defn resolve-job-url [job-url {:keys [job labels]}]
  (reduce
    (fn [^URI uri s]
      (.resolve uri (str (http-util/url-encode s) "/")))
    job-url
    (flatten (into [job] labels))))

(defn extract-expired-job-urls [{:keys [req expiration-time job-url log]}]
  (let [lines (map parse-line (filter #(str/starts-with? % "push_time_seconds") (str/split-lines (:body req))))
        expired-lines (filter #(< (:value %) expiration-time) lines)]
    (log (str "Found " (count lines) " jobs"))
    (log (str (count expired-lines) " expired jobs"))
    (map #(resolve-job-url job-url %)
         expired-lines)))

(defn silent-logger [& args] nil)
(defn stdout-logger [& args] (apply println args))

(defn now-in-ms []
  (inst-ms (java.time.Instant/now)))

(defn push-metric [{:keys [^URI job-url basic-auth success-metric now]}]
  (http/request {:url (.resolve job-url ^String (http-util/url-encode success-metric))
                 :basic-auth basic-auth
                 :method :put
                 :body (str success-metric " " (ms->s now))}))


(defn run [{:keys [log
                   metric-url
                   job-url
                   basic-auth
                   dry-run
                   expiration-in-minutes
                   report-metrics
                   success-metric]}]
  (when dry-run
    (log "Dry run: Won't delete any data"))
  (log "Fetching data from Prometheus pushgateway")
  (let [req (http/request {:url metric-url
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
                          :success-metric success-metric
                          :now (now-in-ms)})
            (log "Metric pushed"))))))


(defn run-in-interval [{:as options :keys [log interval-in-minutes]}]
  (log "Running in interval")
  (loop []
    (log "Running...")
    (run options)
    (log (str "Sleeping for " interval-in-minutes " " (if (> interval-in-minutes 1)
                                                        "minutes"
                                                        "minute")))
    (Thread/sleep (min->ms interval-in-minutes))
    (recur)))


(defn -main
  "Handles args parsing and does the appropriate action."
  [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)
        {:keys [help
                metric-url
                print-version
                silent
                interval-in-minutes]} options
        log (if silent
              silent-logger
              stdout-logger)
        run-options (assoc options
                           :log log)]
    (log "Welcome to Prometheus pushgateway cleaner")
    (log "Started with options:")
    (log (with-out-str
           (print-table (map (fn [[k v]] {"option" (name k)
                                          "value" v})
                             options))))
    (cond
      help          (do (log intro)
                        (log summary))
      print-version (log version)
      errors        (do (run! log errors)
                        (System/exit 1))
      (not metric-url) (do (log "--metric-url is required")
                           (System/exit 1))
      interval-in-minutes (run-in-interval (assoc run-options
                                                  :interval-in-minutes interval-in-minutes))
      :else         (run run-options))))
