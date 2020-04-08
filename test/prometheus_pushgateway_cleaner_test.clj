(ns prometheus-pushgateway-cleaner-test
  (:require [clojure.test :refer [deftest is]]
            [clj-http.lite.client :as http]
            [prometheus-pushgateway-cleaner :as p])
  (:import (java.net URI)))

(defn metric-response []
  {:headers {"server" "nginx/1.17.4"
             "date" "Wed, 26 Feb 2020 10:11:56 GMT"
             "content-type" "text/plain; version=0.0.4; charset=utf-8"
             "transfer-encoding" "chunked"
             "content-encoding" "gzip"}
   :status 200
   :body (slurp "test/test-metrics.prom")})

(defn mock-req [reqs]
  (fn [req]
    (if (= "https://example.com/metrics/" (str (:url req)))
     (metric-response)
     (swap! reqs (fn [r] (conj r req))))))

(defn mock-now []
  (+ (* 1.5827112617848473E9 1000) (p/min->ms 60)))

(def common-args
    {:log (fn [_] nil)
     :metric-url (URI. "https://example.com/metrics/")
     :job-url (URI. "https://example.com/metrics/job@base64/")
     :expiration-in-minutes 60
     :report-metrics false})

(def common-response
  [{:url "https://example.com/metrics/job@base64/YXBp/institute_id@base64/bnVtYmVyIHR3bw=="
    :basic-auth nil
    :method :delete}
   {:url "https://example.com/metrics/job@base64/YXBp/instance@base64/MzA=/institute_id@base64/dGhpcmQtb25l"
    :basic-auth nil
    :method :delete}])

(deftest sends-correct-delete-requests
  (let [reqs (atom [])]
    (with-redefs [p/now-in-ms mock-now
                  http/request (mock-req reqs)]
      (p/run common-args)
      (is (= @reqs
             (conj common-response
                   {:url "https://example.com/metrics/job@base64/c3VjY2Vzcw=="
                    :basic-auth nil
                    :method :delete}))))))

(deftest report-metrics
  (let [reqs (atom [])]
    (with-redefs [p/now-in-ms mock-now
                  http/request (mock-req reqs)]
      (p/run (assoc common-args
                    :report-metrics true
                    :success-metric "success"))
      (is (= @reqs
             (conj common-response
                   {:url (URI. "https://example.com/metrics/job@base64/c3VjY2Vzcw==")
                    :basic-auth nil
                    :method :put
                    :body "success 1582714861\n"}))))))

