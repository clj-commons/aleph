;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http
  (:require
    [aleph.http.websocket :as ws]
    [aleph.http.netty :as http]
    [aleph.formats :as formats]
    [aleph.http.options :as options])
  (:use
    [lamina core executor]
    [potemkin]))

(import-fn ws/websocket-client)

(import-fn http/start-http-server)

(import-fn http/http-connection)
(import-fn http/http-client)
(import-fn http/pipelined-http-client)
(import-fn http/http-request)

(defn sync-http-request
  "Issues a synchronous HTTP request, with the request object based upon the Ring spec,
   with the augmentations used by clj-http."
  ([request]
     (sync-http-request request nil))
  ([request timeout]
     (try
       @(http-request request timeout)
       (catch Exception e
         (throw (Exception. "HTTP request failed" e))))))

(defn wrap-ring-handler
  "Takes a normal Ring handler, and turns it into a handler that can be consumed by Aleph's
   start-http-server.  If the Ring handler returns an async-promise, this will be handled properly
   and sent along as a long-poll response whenever it is realized.

   This should be the outermost middleware around your function.  To use an Aleph handler at a
   particular endpoint within the scope of this middleware, use wrap-aleph-handler."
  [f]
  (fn [ch request]
    (run-pipeline request
      {:error-handler (fn [ex] (error ch ex))} 

      ;; call into handler
      (fn [{:keys [body content-type character-encoding] :as request}]
        (if (channel? body) 

          (if (options/channel-ring-requests? request)

            ;; leave channels as is
            (f (assoc request ::channel ch))

            ;; move onto another thread, since there will be blocking reads
            (task "input-stream-reader"
              (f (assoc request
                   ::channel ch
                   :body (formats/channel->input-stream body character-encoding)))))

          (f (assoc request
               ::channel ch
               :body (formats/bytes->input-stream body character-encoding)))))

      ;; send response
      (fn [response]
        (when-not (::ignore response)
          (enqueue ch response))))))

(defn wrap-aleph-handler
  "Allows a 2-arity Aleph handler to be used within the scope of wrap-ring-handler."
  [f]
  (fn [request]
    (f (::channel request) (dissoc request ::channel))
    {:status 200
     ::ignore true}))

(defn wrap-websocket-handler
  "Allows a 2-arity Aleph handler for websocket connections to be used within the scope of wrap-ring-handler."
  [f]
  (fn [request]
    (if-not (:websocket? request)
      {:status 400
       :headers {:content-type "text/plain"}
       :body "This endpoint is only for WebSocket connections."}
      (do
        (f (::channel request) (dissoc request ::channel))
        {:status 200
         ::ignore true}))))


