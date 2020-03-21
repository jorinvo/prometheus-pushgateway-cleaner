(ns prometheus-pushgateway-cleaner-test
  (:require [clojure.test :refer [deftest is]]
            [clj-http.lite.client :as http]
            [prometheus-pushgateway-cleaner :as p])
  (:import (java.net URI)))

(deftest sends-correct-delete-requests
  (is
    (= (let [metric-response {:headers {"server" "nginx/1.17.4"
                                        "date" "Wed, 26 Feb 2020 10:11:56 GMT"
                                        "content-type" "text/plain; version=0.0.4; charset=utf-8"
                                        "transfer-encoding" "chunked"
                                        "content-encoding" "gzip"}
                              :status 200
                              :body (slurp "test/test-metrics.prom")}
             reqs (atom [])]
         (with-redefs [p/now-in-ms (fn [] (+ (* 1.5827112617848473E9 1000) (p/min->ms 60)))
                       http/request (fn [req]
                                      (if (= "https://example.com/metrics/" (str (:url req)))
                                        metric-response
                                        (swap! reqs (fn [r] (conj r req)))))]
           (p/run {:log (fn [_] nil)
                   :metric-url (URI. "https://example.com/metrics/")
                   :job-url (URI. "https://example.com/metrics/job/")
                   :expiration-in-minutes 60
                   :metric-name "metric"
                   :report-metrics true
                   :success-metric "metric"})
           @reqs))
       [{:url (URI. "https://example.com/metrics/job/api/institute_id/number+two/")
         :basic-auth nil
         :method :delete}
        {:url (URI. "https://example.com/metrics/job/api/institute_id/third-one/")
         :basic-auth nil
         :method :delete}
        {:url (URI. "https://example.com/metrics/job/metric")
         :basic-auth nil
         :method :put
         :body "metric 1582714861\n"}
        ])))


