#!/bin/sh

# expects s3-dl.sh to be on the PATH
# export AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY

s3-dl.sh prcrsr-deploys manifest | xargs s3-dl.sh prcrsr-deploys
