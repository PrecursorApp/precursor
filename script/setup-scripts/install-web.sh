#!/usr/bin/env sh

# Takes 1 argument: the number (from 0-9) that indicates which web server it is
# Need to put production.sh at / before running

set -e
set -x

number=$1

if [ -z "$number" ]; then
    echo "Need to provide number as first argument"
    exit 1
fi

if [ -z "$AWS_ACCESS_KEY_ID" ]; then
    echo "Need to export AWS_ACCESS_KEY_ID"
    exit 1
fi

if [ -z "$AWS_SECRET_ACCESS_KEY" ]; then
    echo "Need to export AWS_SECRET_ACCESS_KEY"
    exit 1
fi

if [ -z "$GPG_PASSPHRASE" ]; then
    echo "Need to export GPG_PASSPHRASE"
    exit 1
fi

# set up private network
host="web${number}"
echo "127.0.0.1 ${host}" >> /etc/hosts

hostname $host

private_ip="10.99.0.11${number}"

# no firewall on ec2
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

# swap's handled for us
# # swap
# dd if=/dev/zero of=/usr/swap0 bs=1m count=8192
# chmod 600 /usr/swap0
# echo 'md99 none swap sw,file=/usr/swap0,late 0 0' >> /etc/fstab
# swapon -aqL

# freebsd-update
freebsd-update fetch
echo '@daily root freebsd-update cron' >> /etc/crontab
## TODO: make the emails go somewhere

# install pkg
ASSUME_ALWAYS_YES=YES pkg bootstrap
pkg update

# java
pkg install --yes java/openjdk7
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

# precursor user
echo '#name:uid:gid:class:change:expire:gecos:home_dir:shell:password' > precursor-user-config
echo 'precursor:::::::::' >> precursor-user-config

adduser -f precursor-user-config
rm precursor-user-config

# web deps
curl --retry 5 --fail https://raw.githubusercontent.com/PrecursorApp/s3-dl/master/s3-dl.sh > /usr/local/sbin/s3-dl.sh
chmod +x /usr/local/sbin/s3-dl.sh

prcrsr_dir="/usr/local/precursor"
mkdir -p $prcrsr_dir
mkdir -p "${prcrsr_dir}/log"

cd $prcrsr_dir
touch pc.log

jar_key=$(s3-dl.sh prcrsr-deploys manifest)
s3-dl.sh prcrsr-deploys $jar_key > pc-standalone.jar

pkg install --yes gnupg

s3-dl.sh prcrsr-secrets web/production.sh.gpg > production.sh.gpg
echo "${GPG_PASSPHRASE}" | gpg --batch --no-tty --decrypt --passphrase-fd 0 production.sh.gpg > production.sh

chown -R precursor:precursor $prcrsr_dir

## TODO: move this into a script and upload a tarball, similar to datomic
java_opts="-Xmx7g -Xms7g -Djava.net.preferIPv4Stack=true -XX:MaxPermSize=256m -XX:+UseConcMarkSweepGC -XX:+CMSClassUnloadingEnabled -Dfile.encoding=UTF-8"
run_cmd="java -server -cp pc-standalone.jar ${java_opts} clojure.main --main pc.init"

. production.sh

daemon -u precursor -p precursor.pid $run_cmd >> daemon.log 2>&1
