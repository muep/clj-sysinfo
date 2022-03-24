# clj-sysinfo
Web service in clojure that provides a description of its environment

This was written to accompany an article published on Solita
[dev blog](https://dev.solita.fi/2022/03/18/running-clojure-on-constrained-memory.html).

## Build and run

Execute by running:

    clojure -M -m sysinfo

Once the program has started, the web api can be queried with HTTP
clients such as `curl`:

    curl localhost:8080/sys-stat
