#!/usr/bin/env sh

## Need to manually scp nginx.conf, haproxy.conf, prcrsr.com.crt, and prcrsr.com.key to /

set -e
set -x

private_ip="10.99.0.103"

# set up private network
echo "ifconfig_vtnet1=\"inet $private_ip netmask 255.255.0.0\"" >> /etc/rc.conf
# hack to get the exit code we need :/
service netif start vtnet1 | grep $private_ip

# Set up firewall
echo 'firewall_enable="YES"' >> /etc/rc.conf
echo 'firewall_quiet="YES"' >> /etc/rc.conf
echo 'firewall_type="workstation"' >> /etc/rc.conf
echo 'firewall_myservices="22 80 443"' >> /etc/rc.conf
echo 'firewall_allowservices="any"' >> /etc/rc.conf
echo 'firewall_logdeny="YES"' >> /etc/rc.conf

echo "ipfw -q add 00001 allow all from any to any via vtnet1" >> /etc/rc.firewall

service ipfw start

echo 'net.inet.ip.fw.verbose_limit=5' >> /etc/sysctl.conf
sysctl net.inet.ip.fw.verbose_limit=5

# time server
echo 'ntpd_enable="YES"' >> /etc/rc.conf
echo 'ntpd_sync_on_start="YES"' >> /etc/rc.conf
service ntpd start

# swap
dd if=/dev/zero of=/usr/swap0 bs=1m count=2048
chmod 600 /usr/swap0
echo 'md99 none swap sw,file=/usr/swap0,late 0 0' >> /etc/fstab
swapon -aqL

# freebsd-update
freebsd-update fetch
echo '@daily root freebsd-update cron' >> /etc/crontab
## TODO: make the emails go somewhere

# install pkg
ASSUME_ALWAYS_YES=YES pkg bootstrap

# nginx
pkg install --yes nginx

echo 'nginx_enable="YES"' >> /etc/rc.conf
mv /nginx.conf /usr/local/etc/nginx/nginx.conf
mkdir /usr/local/etc/nginx/certs
mv /prcrsr.com.crt /usr/local/etc/nginx/certs
mv /prcrsr.com.key /usr/local/etc/nginx/certs
mv /precursorapp.com.crt /usr/local/etc/nginx/certs
mv /precursorapp.com.key /usr/local/etc/nginx/certs

mkdir /var/log/nginx
touch /var/log/nginx/access.log
touch /var/log/nginx/error.log

service nginx start

# haproxy
pkg install --yes haproxy
echo 'haproxy_enable="YES"' >> /etc/rc.conf
mv /haproxy.conf /usr/local/etc/haproxy.conf

touch /var/log/haproxy.log

sed -i '' '/cron*/s/$/\
local0.* \/var\/log\/haproxy.log/' /etc/syslog.conf
echo 'syslogd_enable="YES"' >> /etc/rc.conf
echo 'syslogd_flags="-ss -C"' >> /etc/rc.conf
service syslogd restart

echo '#name:uid:gid:class:change:expire:gecos:home_dir:shell:password' > haproxy-user-config
echo 'haproxy:::::::/nonexistent::' >> haproxy-user-config

adduser -f haproxy-user-config
rm haproxy-user-config

service haproxy start
