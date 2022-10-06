# How to contribute to Aleph

This document is a work in progress!

## Updating dependencies

After you've made your changes to `project.clj`, be sure to run the tests with `lein test`!

Once it's ready to go, you'll need to run the `deps/lein-to-deps` script to update `deps.edn` as well. It's preferable to commit the `deps.edn` changes together with the changes to `project.clj` so the dependency changes are atomic (can be reverted in one step).
