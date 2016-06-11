#!/usr/bin/env sh

set -e
set -x

if [ -z "$LICENSE" ]; then
    echo "Need to export Datomic license as LICENSE"
    exit 1
fi

private_ip="10.99.0.105"
# Using dynamodb on ec2
# postgres_ip="10.99.0.101"
license_key="${LICENSE}"

datomic_version=datomic-pro-0.9.5130
datomic_bucket_url="https://s3-us-west-2.amazonaws.com/prcrsr-datomic"
datomic_download_url="${datomic_bucket_url}/datomic-releases/${datomic_version}.zip"

# No firewall on ec2
# # set up private network
# echo "ifconfig_vtnet1=\"inet $private_ip netmask 255.255.0.0\"" >> /etc/rc.conf
# # hack to get the exit code we need :/
# service netif start vtnet1 | grep $private_ip

# # Set up firewall
# echo 'firewall_enable="YES"' >> /etc/rc.conf
# echo 'firewall_quiet="YES"' >> /etc/rc.conf
# echo 'firewall_type="workstation"' >> /etc/rc.conf
# echo 'firewall_myservices="22"' >> /etc/rc.conf
# echo 'firewall_allowservices="any"' >> /etc/rc.conf
# echo 'firewall_logdeny="YES"' >> /etc/rc.conf

# echo "ipfw -q add 00001 allow all from any to any via vtnet1" >> /etc/rc.firewall

# service ipfw start

# echo 'net.inet.ip.fw.verbose_limit=5' >> /etc/sysctl.conf
# sysctl net.inet.ip.fw.verbose_limit=5

# time server
echo 'ntpd_enable="YES"' >> /etc/rc.conf
echo 'ntpd_sync_on_start="YES"' >> /etc/rc.conf
service ntpd start

# swap
dd if=/dev/zero of=/usr/swap0 bs=1m count=4096
chmod 600 /usr/swap0
echo 'md99 none swap sw,file=/usr/swap0,late 0 0' >> /etc/fstab
swapon -aqL

# freebsd-update
freebsd-update fetch
echo '@daily root freebsd-update cron' >> /etc/crontab
## TODO: make the emails go somewhere

# install pkg
ASSUME_ALWAYS_YES=YES pkg bootstrap

# java
pkg install --yes java/openjdk8
mount -t fdescfs fdesc /dev/fd
mount -t procfs proc /proc

echo 'fdesc   /dev/fd         fdescfs         rw      0       0' >> /etc/fstab
echo 'proc    /proc           procfs          rw      0       0' >> /etc/fstab

# bash
pkg install --yes bash
mount -t fdescfs fdesc /dev/fd
echo 'fdesc   /dev/fd         fdescfs         rw      0       0' >> /etc/fstab
ln -s /usr/local/bin/bash /bin/bash

# curl
pkg install --yes curl

alias safe-curl="curl --retry 5 --fail"

# datomic
safe-curl $datomic_download_url > "${datomic_version}.zip"
unzip ${datomic_version}.zip
rm ${datomic_version}.zip
mv ${datomic_version} /usr/local/datomic
cd /usr/local/datomic
# sql storage
# sed s/license-key=// config/samples/sql-transactor-template.properties > config/transactor.properties
# sed -i "" "s/localhost:5432/$postgres_ip:5432/" config/transactor.properties

# ddb storage
sed s/license-key=// config/samples/ddb-transactor-template.properties > config/transactor.properties
sed -i "" "s/aws-dynamodb-table=your-system-name/aws-dynamodb-table=prcrsr-datomic/" config/transactor.properties
sed -i "" "s/aws-dynamodb-region=us-east-1/aws-dynamodb-region=us-west-2/" config/transactor.properties
sed -i "" "s/aws-transactor-role=/aws-transactor-role=prcrsr-datomic/" config/transactor.properties
sed -i "" "s/aws-peer-role=/aws-peer-role=prcrsr-datomic-peer/" config/transactor.properties
sed -i "" "s/# aws-s3-log-bucket-id=/aws-s3-log-bucket-id=prcrsr-datomic-logs/" config/transactor.properties
sed -i "" "s/# aws-cloudwatch-region=/aws-cloudwatch-region=us-west-2/" config/transactor.properties
sed -i "" "s/# aws-cloudwatch-dimension-value=your-system-name/aws-cloudwatch-dimension-value=prcrsr-datomic/" config/transactor.properties

# everything
sed -i "" "s/memory-index-max=256m/memory-index-max=512m/" config/transactor.properties
sed -i "" "s/object-cache-max=128m/object-cache-max=1g/" config/transactor.properties
sed -i "" "s/host=localhost/host=$private_ip/" config/transactor.properties
echo "license-key=$license_key" >> config/transactor.properties

safe-curl "${datomic_bucket_url}/conf/logback.xml" > /usr/local/datomic/bin/logback.xml

mkdir -p /usr/local/etc/rc.d
safe-curl "${datomic_bucket_url}/conf/rc.d/datomic" > /usr/local/etc/rc.d/datomic
chmod +x /usr/local/etc/rc.d/datomic

echo 'datomic_enable="YES"' >> /etc/rc.conf
service datomic start
