#!/usr/bin/env sh

set -e
set -x

# set up private network
private_ip="10.99.0.106"
echo "ifconfig_vtnet1=\"inet $private_ip netmask 255.255.0.0\"" >> /etc/rc.conf
# hack to get the exit code we need :/
service netif start vtnet1 | grep $private_ip

# Set up firewall
echo 'firewall_enable="YES"' >> /etc/rc.conf
echo 'firewall_quiet="YES"' >> /etc/rc.conf
echo 'firewall_type="workstation"' >> /etc/rc.conf
echo 'firewall_myservices="22"' >> /etc/rc.conf
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

# memcached
echo 'memcached_enable="YES"' >> /etc/rc.conf
echo 'memcached_flags="-m 700 -v"' >> /etc/rc.conf
pkg install --yes memcached
service memcached start
