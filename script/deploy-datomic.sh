#!/usr/bin/env bash

set -x
set -e

# pass the path where datomic was unzipped as the first argument
install_path=$1

version="0.9.5130"
jar_path="${install_path}/datomic-pro-${version}.jar"
pom_path="${install_path}/pom.xml"

lein deploy prcrsr-s3-releases com.datomic/datomic-pro $version $jar_path $pom_path
