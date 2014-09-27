CI.Versions =
  v: (v) ->
    "#{v} #{CI.Versions[v]}"

  Firefox: "26.0"
  Chrome: "33.0.1750.152"
  chromedriver: "2.9"


  default_ruby: "1.9.3-p448"
  old_ruby: "1.8.7-p358"
  ruby_versions: [
    "1.8.7-p302",
    "1.8.7-p334",
    "1.8.7-p352",
    "1.8.7-p357",
    "1.8.7-p358",
    "1.8.7-p370",
    "1.8.7-p371",
    "1.8.7-p374",
    "ree-1.8.7-2011.02",
    "ree-1.8.7-2011.03",
    "ree-1.8.7-2011.12",
    "ree-1.8.7-2012.02",
    "1.9.2-p0",
    "1.9.2-p136",
    "1.9.2-p180",
    "1.9.2-p290",
    "1.9.2-p320",
    "1.9.3-p0-falcon",
    "1.9.3-p0",
    "1.9.3-p125",
    "1.9.3-p194",
    "1.9.3-p194-falcon",
    "1.9.3-p286",
    "1.9.3-p327",
    "1.9.3-p327-falcon",
    "1.9.3-p327-railsexpress",
    "1.9.3-p362",
    "1.9.3-p374",
    "1.9.3-p385",
    "1.9.3-p392",
    "1.9.3-p429",
    "1.9.3-p448",
    "1.9.3-p484",
    "1.9.3-p484-railsexpress",
    "1.9.3-p545",
    "2.0.0-p0",
    "2.0.0-p195",
    "2.0.0-p247",
    "2.0.0-p353",
    "2.0.0-p353-railsexpress",
    "2.0.0-p451",
    "2.0.0-p481",
    "2.1.0-preview1",
    "2.1.0-preview2",
    "2.1.0",
    "2.1.0-p0",
    "2.1.1",
    "2.1.2",
    "2.1.2-railsexpress",
    "jruby-1.7.0",
    "jruby-1.7.3",
    "jruby-1.7.4",
    "jruby-1.7.10"
    "jruby-1.7.5",
    "jruby-1.7.6",
    "jruby-1.7.8",
    "jruby-1.7.9",
    "jruby-1.7.10",
    "jruby-1.7.11",
    "jruby-1.7.12",
    "rbx-2.2.6"
    ]
  bundler: "1.6.3"
  cucumber: "1.2.0"
  rspec: "2.14.4"
  rake: "10.1.0"

  default_node: "0.8.12"
  node_versions: [
     "0.11.13",
     "0.11.8",
     "0.10.28",
     "0.10.26",
     "0.10.24",
     "0.10.22",
     "0.10.21",
     "0.10.20",
     "0.10.11",
     "0.10.5",
     "0.10.0",
     "0.8.24",
     "0.8.22",
     "0.8.19",
     "0.8.12",
     "v0.8.2"
  ]

  lein: "2.3.1"
  ant: "1.8.2"
  maven: "3.2.1"

  default_python: "2.7.3"
  python: "2.7.3"
  python_versions: [
     "2.6.8",
     "2.7",
     "2.7.3",
     "2.7.4",
     "2.7.5",
     "2.7.6"
     "2.7.7"
     "2.7.8"
     "3.1.5",
     "3.2",
     "3.2.5",
     "3.3.0",
     "3.3.2",
     "3.3.3",
     "3.4.0",
     "3.4.1",
     "pypy-2.2.1"
  ]
  pip: "1.5.6"
  virtualenv: "1.11.6"

  default_php: "5.3.10-1ubuntu3.7"
  php: "5.3.10-1ubuntu3.5"
  php_versions: [
    "5.3.3",
    "5.3.10",
    "5.3.20",
    "5.3.25",
    "5.4.4",
    "5.4.5",
    "5.4.6",
    "5.4.7",
    "5.4.8",
    "5.4.9",
    "5.4.10",
    "5.4.11",
    "5.4.12",
    "5.4.13",
    "5.4.14",
    "5.4.15",
    "5.4.18",
    "5.4.19",
    "5.4.21",
    "5.5.0",
    "5.5.2",
    "5.5.3",
    "5.5.7",
    "5.5.8",
    "5.5.11"
  ]

  golang: '1.3'
  erlang: 'r14b04'

  default_java_package: "oraclejdk7"
  default_java_version: "1.7.0_55"
  java_packages: [
    { name: "Oracle JDK 8", package: "oraclejdk8", version: "1.8.0" },
    { name: "Oracle JDK 7", package: "oraclejdk7", version: "1.7.0_55", default: true },
    { name: "Oracle JDK 6", package: "oraclejdk6", version: "1.6.0_37" },
    { name: "Open JDK 7", package: "openjdk7" },
    { name: "Open JDK 6", package: "openjdk6" }
  ]

  gradle: "1.10"
  play: "2.2.1"
  scala_versions: [
    "0.11.3",
    "0.12.0",
    "0.12.1",
    "0.12.2",
    "0.12.3",
    "0.12.4",
    "0.13.0",
    "0.13.1"]

  solr: "4.3.1"
  postgresql: "9.3"
  mysql: "5.5.37"
  mongodb: "2.4.10"
  riak: "1.4.8-1"
  cassandra: "2.0.6"
  redis: "2.8.12"
  memcached: "1.4.13"
  sphinx: "2.0.4-release"
  elasticsearch: "0.90.2"
  beanstalkd: "1.4.6"
  couchbase: "2.0.0"
  couchdb: "1.3.0"
  neo4j: "2.1.2"
  rabbitmq: "3.3.4"


  git: "1.8.5.5"

  ghc: "7.6.3"
  ghc_versions: [
    "7.4.2",
    "7.6.3",
    "7.8.2",
    "7.8.3"]

  gcc: "4.6.3-1ubuntu5"
  "g++": "4.6.3-1ubuntu5"
  casperjs: "1.0.9"
  phantomjs: "1.9.7"
