#!/bin/sh

mkdir -pv classes target

clojure -M -e "(compile 'sysinfo)"
clojure -M:uberdeps --main-class sysinfo
