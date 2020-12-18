(ns prometheus-pushgateway-cleaner
  (:require [clojure.string :as str]
            [clojure.pprint :refer [print-table]]
            [clj-http.lite.client :as http]
            [clojure.tools.cli :as cli])
  (:import (java.net URI URLEncoder)
           (java.util Base64))
  (:gen-class))

(set! *warn-on-reflection* true)


(def version
  (or (System/getProperty "version")
      "development"))


(def intro "
> Delete old metric jobs from Prometheus pushgateway

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
    :desc "Push success-metric to pushgateway which contains a unix timestamp (in s) for the last sucessful cleaning"]
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


(defn silent-logger [& args] nil)
(defn stdout-logger [& args] (apply println args))


(defn now-in-ms []
  (inst-ms (java.time.Instant/now)))


(defn encode-base64 [^String to-encode]
  (-> (Base64/getUrlEncoder)
      .withoutPadding
      (.encodeToString (.getBytes to-encode))))

(defn base64-suffix [s]
  (str s "@base64"))

(defn parse-line [line]
  (let [[_ lstr v] (re-matches #"^push_time_seconds\{(.+)\} (.+)" line)
        all-labels (->> (str/split lstr #"\"")
                        (partition 2 )
                        (map (fn [[a b]]
                               (when (< 0 (count b))
                                 [(second (re-matches #"^,?(.+)=$" a)) b])))
                        (filter some?)
                        (into {}))]
    {:value (s->ms (Double/parseDouble v))
     :job (get all-labels "job")
     :labels (dissoc all-labels "job")}))

(defn resolve-job-url [job-url {:keys [job labels]}]
  (->> labels
       (map (fn [[k v]]
              [(base64-suffix (URLEncoder/encode ^String k "UTF-8"))
               (encode-base64 v)]))
       (into [(encode-base64 job)])
       flatten
       (reduce
         (fn [^URI uri ^String s]
           (.resolve uri (str s "/")))
         job-url)))

(defn extract-expired-job-urls
  [{:keys [metrics
           now
           expiration-in-minutes
           report-metrics
           success-metric
           job-url
           log]}]
  (let [lines (->> metrics
                   str/split-lines
                   (filter #(str/starts-with? % "push_time_seconds"))
                   (map parse-line)
                   (remove #(and report-metrics (= (:job %) success-metric))))
        expiration-in-ms (min->ms expiration-in-minutes)
        expiration-time (- now expiration-in-ms)
        expired-lines (->> lines
                           (filter #(< (:value %) expiration-time)))]
    (log (str "Found jobs: " (count lines)))
    (log (str "Expired jobs: " (count expired-lines)))
    (->> expired-lines
         (map #(resolve-job-url job-url %))
         (map #(-> % str drop-last str/join)))))

(comment
  (extract-expired-job-urls {:metrics (slurp "test/test-metrics.prom")
                             :now (now-in-ms)
                             :expiration-in-minutes 60
                             :job-url (URI. "http://example.com/metrics/job@base64/")
                             :log silent-logger}))


(defn push-metric
  [{:keys [^URI job-url
           basic-auth
           ^String success-metric
           now]}]
  (http/request {:url (.resolve job-url ^String (encode-base64 success-metric))
                 :basic-auth basic-auth
                 :method :put
                 :body (str success-metric " " (long (ms->s now)) "\n")}))

(defn run
  [{:keys [log
           ^URI metric-url
           job-url
           basic-auth
           dry-run
           expiration-in-minutes
           report-metrics
           success-metric]}]
  (when dry-run
    (log "Dry run: Won't delete any data"))
  (log "Fetching data from Prometheus pushgateway")
  (let [{:keys [body]} (http/request
                         {:url metric-url
                          :basic-auth basic-auth
                          :method :get})
        now (now-in-ms)
        job-url (.resolve metric-url "job@base64/")
        expired-job-urls (extract-expired-job-urls
                           {:metrics body
                            :now now
                            :report-metrics report-metrics
                            :success-metric success-metric
                            :expiration-in-minutes expiration-in-minutes
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
                          :now now})
            (log "Metric pushed"))))))


(defn run-in-interval
  [{:as options :keys [log interval-in-minutes]}]
  (log "Running in interval")
  (loop []
    (log "Running...")
    (run options)
    (log (str "Sleeping for " interval-in-minutes " " (if (> interval-in-minutes 1)
                                                        "minutes"
                                                        "minute") "\n"))
    (Thread/sleep (min->ms interval-in-minutes))
    (recur)))


(defn -main
  "Handles args parsing and does the appropriate action"
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
    (cond
      help          (do (log intro)
                        (log summary)
                        (System/exit 0))
      print-version (do (log version)
                        (System/exit 0)))
    (log "Welcome to Prometheus pushgateway cleaner")
    (log "Started with options:")
    (log (with-out-str
           (print-table (map (fn [[k v]] {"option" (name k)
                                          "value" v})
                             options))))
    (cond
      errors              (do (run! log errors)
                              (log "\n\nSee ` -h` for more.")
                              (System/exit 1))
      (not metric-url)    (do (log "--metric-url is required\n\nSee ` -h` for more.")
                              (System/exit 1))
      interval-in-minutes (run-in-interval (assoc run-options
                                                  :interval-in-minutes interval-in-minutes))
      :else               (run run-options))))
