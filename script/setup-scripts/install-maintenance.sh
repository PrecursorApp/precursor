#!/usr/bin/env sh

set -e
set -x

private_ip="10.99.0.104"

if [ -z "$LIBRATO_TOKEN" ]; then
    echo "Need to export LIBRATO_TOKEN"
    exit 1
fi

# no firewall in ec2
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

# swap is handled by default in ec2
# # swap
# dd if=/dev/zero of=/usr/swap0 bs=1m count=2048
# chmod 600 /usr/swap0
# echo 'md99 none swap sw,file=/usr/swap0,late 0 0' >> /etc/fstab
# swapon -aqL

# freebsd-update
freebsd-update fetch
echo '@daily root freebsd-update cron' >> /etc/crontab
## TODO: make the emails go somewhere

# install pkg
ASSUME_ALWAYS_YES=YES pkg bootstrap

# syslog
sed -i '' '/cron*/s/$/\
statsd* \/var\/log\/statsd.log/' /etc/syslog.conf
echo 'syslogd_enable="YES"' >> /etc/rc.conf
echo 'syslogd_flags="-ss -C"' >> /etc/rc.conf
service syslogd restart


# statsd
pkg install --yes net-mgmt/statsd
pkg install --yes www/npm
cd /usr/local/share/statsd
npm install statsd-librato-backend

echo "{                                            " > /usr/local/etc/statsd.js
echo "  port: 8125,                                " >> /usr/local/etc/statsd.js
echo "  backends: [\"statsd-librato-backend\"],    " >> /usr/local/etc/statsd.js
echo "  librato: {                                 " >> /usr/local/etc/statsd.js
echo "    email: \"daniel+start-trial@prcrsr.com\"," >> /usr/local/etc/statsd.js
echo "    token: \"${LIBRATO_TOKEN}\",             " >> /usr/local/etc/statsd.js
echo "    source: \"${private_ip}\",               " >> /usr/local/etc/statsd.js
echo "    includeMetrics: [/.*/],                  " >> /usr/local/etc/statsd.js
echo "  },                                         " >> /usr/local/etc/statsd.js
echo "  deleteIdleStats: true,                     " >> /usr/local/etc/statsd.js
echo "  debug: true,                               " >> /usr/local/etc/statsd.js
echo "}                                            " >> /usr/local/etc/statsd.js

echo 'statsd_enable="YES"' >> /etc/rc.conf

sed -i "" "s/command_args=\"-cf/command_args=\"-c/" /usr/local/etc/rc.d/statsd
sed -i "" "s/^command_args=\(.*\)\"$/command_args=\1 > \/var\/log\/statsd.log 2>\&1\"/" /usr/local/etc/rc.d/statsd

touch /var/log/statsd.log
chown statsd /var/log/statsd.log

service statsd start

# memcached
echo 'memcached_enable="YES"' >> /etc/rc.conf
echo 'memcached_flags="-m 700 -v"' >> /etc/rc.conf
pkg install --yes memcached
service memcached start

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

alias safe-curl="curl --retry 5 --fail"

# datomic
safe-curl $datomic_download_url > "${datomic_version}.zip"
unzip ${datomic_version}.zip
rm ${datomic_version}.zip
mv ${datomic_version} /usr/local/datomic
cd /usr/local/datomic

# manually set up the cronjob for now
