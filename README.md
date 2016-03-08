# Puu

[![Build Status](https://drone.io/bitbucket.org/niklas_collin/puu/status.png)](https://drone.io/bitbucket.org/niklas_collin/puu/latest)

**Puu** is a Clojure(Script) library for managing versioned application state. It provides a concurrently safe way to
store versioned data in a single mutable storage. In Clojure this is achieved by using `ref` and Clojure's STM, in
ClojureScript `atom` is used. The way **Puu** differs from traditional Clojure is that it gives you:

* Version history
    * You can define how many versions `manager` will keep in memory. Default is 1000.
* Changeset calculation
    * you can calculate a changeset between two different versions which can be as far apart from each other as you like
* Changeset applying
    * you can apply generated changeset to another dataset thus allowing distributed data transfers
* Subscribing on new versions
    * you can provide a `core.async` `chan` to `subscribe`
    * when new version is added to `manager` all channels are populated with the new model data
    * this allows asynchronous reaction of changing data in CSP manner

## Usage

**TODO**: for now see tests for usage examples.

## License

Copyright © 2016 Niklas Collin

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
