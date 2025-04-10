# How to contribute to Aleph

Thanks for helping out! Aleph is a collaborative project that wouldn’t exist without the generous time and effort of many people.

This document is a work in progress, so if there's anything you feel needs to be added, please let us know, or file a PR.

## Local development environment

We require a recent version of [Leiningen](https://leiningen.org/), and a minimum Java version of 8. Running the deps update script will require bash on your system. Other than that, we have no specific requirements.

### TLS/SSL

To develop against TLS, (effectively mandatory for HTTP/2+) it's helpful to install
your own root authority and certificates. The [mkcert](https://github.com/FiloSottile/mkcert) tool is ideal for that. 
While a self-signed certificate will work, it's possible to run into warnings and
odd behavior.

#### Mkcert
An example setup:
```shell
# first check $JAVA_HOME is not empty, mkcert will use it to know where to install
echo $JAVA_HOME

# this is installs the root certificate authority (CA) for browsers/OS/Java
mkcert -install 

# if you have multiple JVMs in use, you will need to install the CA for each one separately
export JAVA_HOME=/path/to/some/other/jdk
TRUST_STORES=java mkcert -install

# this will generate a cert file and a key file in .pem format
mkcert aleph.localhost localhost 127.0.0.1 ::1
# e.g., aleph.localhost+3.pem and aleph.localhost+3-key.pem 
```

If you are using an HTTP tool with its own trust store, like Insomnia, you will
need to add the root CA to its trust store as well.

For Insomnia, it's hidden under the project dropdown in the top center, under 
Collection Settings > Client Certificates > CA Certificate. (NB: you don't need
a client certificate, just the CA.)

For curl, you would run something like: `curl --cacert "$(mkcert -CAROOT)/rootCA.pem"`

Warning: As of August 2023, many tools still do not support HTTP/2: Postman, 
HTTPie, RapidAPI/Paw, and many others.

#### DNS
You'll need to add `aleph.localhost` to your `/etc/hosts` file, e.g.:

```
127.0.0.1 aleph.localhost
::1 aleph.localhost
```

#### Aleph SslContext
Then, in code, generate an SslContext like:

```clojure
(aleph.netty/ssl-server-context
  {:certificate-chain "/path/to/aleph.localhost+3.pem"
   :private-key       "/path/to/aleph.localhost+3-key.pem"
   ...})
```

#### Wireshark and curl

If you need to debug at the wire level, Wireshark is a powerful, but difficult 
to use, tool. It supports TLS decryption, not via the use of certificates (which are
only a starting point), but by recording the actual TLS session keys to a file. 

In curl, you can support this by adding an env var called `SSLKEYLOGFILE`, and in
Wireshark's Preferences > Protocols > TLS, set the "(Pre)-Master-Secret log 
filename" to the same value. This is a bit of a pain to set up, but once done, 
it's very useful.

You would then run something like:

```shell
curl --cacert "$(mkcert -CAROOT)/rootCA.pem" --http2-prior-knowledge --max-time 3 --tls-max 1.2 https://aleph.localhost:11256/
```

*Note* the `--tls-max 1.2`. In testing, it was required or else the session key 
log file was empty.

## Testing

`lein test` should be run, and pass, before pushing up to GitHub.

If you are fixing a bug, create a test that demonstrates it _first_, then write the fix. This ensures our understanding of the problem is correct.

## Linting

[clj-kondo](https://github.com/clj-kondo/clj-kondo) is recommended for linting. Because of the potemkin library and various proto-potemkin macros, Aleph can trigger a lot of false positives, and clj-kondo is the least bad at understanding them. (Handling this is ongoing; PRs welcome.) 

## Formatting

Generally, we try to follow the [Clojure Style Guide](https://guide.clojure.style/). However, there are a few caveats. Due to Aleph's age, it predates a common Clojure style, so much of it will not follow the guide. 

While it's tempting to reformat things left and right, this has the downside of creating a lot of irrelevant diffs in any PR. To keep PR review manageable, please only reformat relevant code.

## Updating dependencies

There are some extra steps to test when updating dependencies:

1. After you've made your changes to `project.clj`, be sure to rerun the tests with `lein test`.

2. When you’re ready to commit, run the `deps/lein-to-deps` script to update `deps.edn` as well, so we can support `deps.edn` users. 

3. Commit the `project.clj` deps changes and the `deps.edn` changes at the same time, so the dependency changes are atomic (i.e., can be reverted in one step).

## CI changes

Aleph, like the rest of the clj-commons repos, uses CircleCI. Improvements to our existing workflow are welcome, but be sure to coordinate with Erik Assum (slipset), the clj-commons CI admin. He may know of improvements that already exist in another repo, have suggestions, or want to copy any improvements to other repos.

## Pull requests

1. When ready, make a PR against the `master` branch. The relevant reviewers (currently kingmob, arnaudgeiser, and dergutemoritz) will be automatically added.
2. Typically, we will discuss it with you a bit, and leave comments, suggestions, and requests for changes. In the interest of time, we may approve something under the condition that something is changed/fixed.
3. After approving, I may ask you to clean up the branch history if it’s particularly messy.
4. One of the reviewers will then merge.
5. Releases should be independent of PRs; please do not change the Aleph version.

## Documentation

While the aleph.io literary doc site is nice, cljdoc.org has emerged as the modern standard, and we wish to support that. Please ensure that all docstrings and articles support it.

Aleph was largely the creation of one person, and could use improvements in documentation. In particular, feel free to add comments explaining the _why_ of a decision or piece of code, even pre-existing.
