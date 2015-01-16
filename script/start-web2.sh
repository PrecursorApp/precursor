#!/usr/bin/env bash

source production.sh

PRODUCTION=true NREPL_PORT=6006 HTTP_PORT=8081 HTTPS_PORT=443 lein run
