#!/bin/sh

aws_id="${AWS_ACCESS_KEY_ID}"
aws_key="${AWS_SECRET_ACCESS_KEY}"
region="${AWS_REGION}"

if [ -z "${region}" ]; then
    region="us-west-2"
fi

bucket="$1"
key="$2"

ensure_var()
{
    if [ -z "$1" ]; then
        echo "$2"
        exit 1
    fi
}

ensure_var "${aws_id}" "AWS_ACCESS_KEY_ID must be exported"
ensure_var "${aws_key}" "AWS_SECRET_ACCESS_KEY must be exported"
ensure_var "${region}" "AWS_REGION must be exported"
ensure_var "${bucket}" "bucket must be provided as the first argument"
ensure_var "${key}" "key must be provided as the second argument"

verb="GET"
md5=""
type=""
date=$(LC_ALL=C date -u +"%a, %d %b %Y %X %z")
amz_headers=""
resource="/${bucket}/${key}"

string_to_sign="${verb}\n${md5}\n${type}\n${date}\n${amx_headers}${resource}"
sig=$(printf "${string_to_sign}" | openssl dgst -sha1 -binary -hmac "${aws_key}" | openssl enc -base64)
auth_header="Authorization: AWS ${aws_id}:${sig}"

curl --retry 5 --fail \
     -H "${auth_header}" -H "Date: ${date}" \
     "https://s3-${region}.amazonaws.com${resource}"
