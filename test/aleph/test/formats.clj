;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.formats
  (:use
    [aleph formats]
    [clojure test]))

(def prxml-greeting [:p {:class "greet"} [:i "Ladies & gentlemen"]])

(def element-greeting
  {:tag :p
   :attrs {:class "greet"}
   :content '(
     {:tag :i
      :attrs {}
      :content ["Ladies & gentlemen"]})})

(deftest test-encode-xml
  (is (=
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><p class=\"greet\"><i>Ladies &amp; gentlemen</i></p>"
        (encode-xml->string prxml-greeting)
        (channel-buffer->string (encode-xml->bytes prxml-greeting))))
  (is (=
        "<?xml version=\"1.0\" encoding=\"KOI8-R\"?><p class=\"greet\"><i>Ladies &amp; gentlemen</i></p>"
        (encode-xml->string prxml-greeting "KOI8-R")
        (channel-buffer->string (encode-xml->bytes prxml-greeting "KOI8-R"))))

  ; maps are emitted with clojure.xml, which uses single quotes, emits newlines, and doesn't entity-escape strings.
  (is (=
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><p class='greet'>\n<i>\nLadies & gentlemen\n</i>\n</p>\n"
        (encode-xml->string element-greeting)
        (channel-buffer->string (encode-xml->bytes element-greeting))))

  (is (=
        "<?xml version=\"1.0\" encoding=\"KOI8-R\"?><p class='greet'>\n<i>\nLadies & gentlemen\n</i>\n</p>\n"
        (encode-xml->string element-greeting "KOI8-R")
        (channel-buffer->string (encode-xml->bytes element-greeting "KOI8-R")))))
