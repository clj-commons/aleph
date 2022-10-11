# How to contribute to Aleph

Thanks for helping out! Aleph is a collaborative project that wouldn’t exist without the generous time and effort of many people.

This document is a work in progress, so if there's anything you feel needs to be added, pleae let us know, or file a PR.

## Local development environment

We require a recent version of [Leiningen](https://leiningen.org/), and a minimum Java version of 8. Running the deps update script will require bash on your system. Other than that, we have no specific requirements.

## Testing

`lein test` should be run, and pass, before pushing up to GitHub.

If you are fixing a bug, create a test that demonstrates it _first_, then write the fix. This ensures our understanding of the problem is correct.

## Linting

[clj-kondo](https://github.com/clj-kondo/clj-kondo) is recommended for linting. Because of the potemkin library and various proto-potemkin macros, Aleph can trigger a lot of false positives, and clj-kondo is the least bad at understanding them. (Handling this is ongoing; PRs welcome.) 

## Formatting

Generally, we try to follow the [Clojure Style Guide](https://guide.clojure.style/). However, there are a few caveats. Due to Aleph's age, it predates a common Clojure style, so much of it will not follow the guide. 

While it's tempting to reformat things left and right, this has the downside of creating a lot of irrelevant diffs in any PR. To keep PR review manageable, please only reformat relevant code.

## Updating dependencies

There’s some extra steps to test when updating dependencies:

1. Obviously, after you've made your changes to `project.clj`, be sure to rerun the tests with `lein test`.

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
