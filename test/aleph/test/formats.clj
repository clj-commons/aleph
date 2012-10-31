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

;;;

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

;;;

(def text
  "The suburb of Saffron Park lay on the sunset side of London, as red and ragged as a cloud of sunset. It was built of a bright brick throughout; its sky-line was fantastic, and even its ground plan was wild. It had been the outburst of a speculative builder, faintly tinged with art, who called its architecture sometimes Elizabethan and sometimes Queen Anne, apparently under the impression that the two sovereigns were identical. It was described with some justice as an artistic colony, though it never in any definable way produced any art. But although its pretensions to be an intellectual centre were a little vague, its pretensions to be a pleasant place were quite indisputable. The stranger who looked for the first time at the quaint red houses could only think how very oddly shaped the people must be who could fit in to them. Nor when he met the people was he disappointed in this respect. The place was not only pleasant, but perfect, if once he could regard it not as a deception but rather as a dream. Even if the people were not \"artists,\" the whole was nevertheless artistic. That young man with the long, auburn hair and the impudent face—that young man was not really a poet; but surely he was a poem. That old gentleman with the wild, white beard and the wild, white hat—that venerable humbug was not really a philosopher; but at least he was the cause of philosophy in others. That scientific gentleman with the bald, egg-like head and the bare, bird-like neck had no real right to the airs of science that he assumed. He had not discovered anything new in biology; but what biological creature could he have discovered more singular than himself? Thus, and thus only, the whole place had properly to be regarded; it had to be considered not so much as a workshop for artists, but as a frail but finished work of art. A man who stepped into its social atmosphere felt as if he had stepped into a written comedy.")

(deftest test-base64
  (is (= text (-> text base64-encode base64-decode bytes->string)))
  (is (= text (-> text (base64-encode true) url-encode base64-decode bytes->string))))

(deftest test-gzip
  (is (= text (-> text gzip-bytes ungzip-bytes bytes->string))))
