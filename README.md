# Puu

**Puu** is a Clojure library for managing versioned application state. It is a glorified `ref` by using the Clojure STM
under the hood and storing everything in a single `ref` per created `manager`. The way **Puu** differs from
traditional Clojure is that it gives you:

* Version history built on top of Java's `SoftReference`
    * you can retrieve any old version as long as they are still in memory
* Changeset calculation
    * you can calculate a changeset between two different versions which can be as far apart from each other as you like
* Changeset applying
    * you can apply generated changeset to another dataset thus allowing distributed data transfers
* Waiting on new versions
    * you can retrieve a `Future` which will give you the new version data when it comes available
    * multiple waiting `Future` instances can be used in which case all of them will receive the same value
    * if you start waiting within one second (currently not configurable) from receiving the value the `manager` will
      queue all versions for you created after you received the last value, thus you will not miss any versions
    * if however you *do* wish to skip intermediary versions then you can just provide a new waiting name each time
      you request a new `Future`

## Usage

**TODO**: for now see tests for usage examples.

## License

Copyright © 2016 Niklas Collin

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
