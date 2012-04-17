;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http
  (:require
    [aleph.http.netty :as http]
    [aleph.formats :as formats]
    [aleph.http.options :as options])
  (:use
    [lamina.core]
    [potemkin]))

(import-fn http/start-http-server)

(import-fn http/http-connection)
(import-fn http/http-client)
(import-fn http/pipelined-http-client)
(import-fn http/http-request)

(defn sync-http-request
  ([request]
     (sync-http-request request nil))
  ([request timeout]
     @(http-request request timeout)))

(defn wrap-ring-handler [f]
  (fn [ch request]
    (run-pipeline request
      {:error-handler (fn [ex] (error ch ex))} 

      ;; transform body
      (if (options/streaming-ring-requests?)
        identity
        (fn [{:keys [body] :as request}]

          ;; if it's a streaming request, wait for it to complete
          (if (channel? body)
            (run-pipeline nil
              {:error-handler (fn [_])}
              (fn [_]
                (if (channel? body)
                  (reduce* conj [] body)
                  body))
              #(update-in request [:body]
                 (when-not (empty? %)
                   (formats/bytes->input-stream %))))
            request)))

      ;; call into handler
      (fn [request]
        (f (assoc request ::channel ch)))

      ;; send response
      (fn [response]
        (when-not (::ignore response)
          (enqueue ch response))))))

(defn wrap-aleph-handler [f]
  (fn [request]
    (f (::channel request) (dissoc request ::channel))
    {:status 200
     ::ignore true}))




