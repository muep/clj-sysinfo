#!/bin/sh

mkdir -pv classes target

clojure -M -e "(compile 'sysinfo) (compile 'sysinfo.lambda-handler)"
clojure -M:uberdeps --main-class sysinfo
