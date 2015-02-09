#!/usr/bin/env sh

set -e
set -x

# set up private network
private_ip="10.99.0.101"
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

# postgres
echo 'postgresql_enable="YES"' >> /etc/rc.conf
pkg install --yes postgresql94-server-9.4.0
/usr/local/etc/rc.d/postgresql initdb
echo "listen_addresses = '*'"  >> /usr/local/pgsql/data/postgresql.conf
echo 'host  all  all  10.99.0.0/24 trust' >> /usr/local/pgsql/data/pg_hba.conf
service postgresql start
su pgsql -c 'createuser -sdr postgres'

echo "-- Database: datomic"            > db.sql
echo ""                                >> db.sql
echo "-- DROP DATABASE datomic;"       >> db.sql
echo ""                                >> db.sql
echo "CREATE DATABASE datomic"         >> db.sql
echo " WITH OWNER = postgres"          >> db.sql
echo "      TEMPLATE template0"        >> db.sql
echo "      ENCODING = 'UTF8'"         >> db.sql
echo "      TABLESPACE = pg_default"   >> db.sql
echo "      LC_COLLATE = 'en_US.UTF-8'">> db.sql
echo "      LC_CTYPE = 'en_US.UTF-8'"  >> db.sql
echo "      CONNECTION LIMIT = -1;"    >> db.sql

psql -f db.sql -U postgres
rm db.sql

echo "-- Table: datomic_kvs"                       > table.sql
echo ""                                            >> table.sql
echo "-- DROP TABLE datomic_kvs;"                  >> table.sql
echo ""                                            >> table.sql
echo "CREATE TABLE datomic_kvs"                    >> table.sql
echo "("                                           >> table.sql
echo " id text NOT NULL,"                          >> table.sql
echo " rev integer,"                               >> table.sql
echo " map text,"                                  >> table.sql
echo " val bytea,"                                 >> table.sql
echo " CONSTRAINT pk_id PRIMARY KEY (id )"         >> table.sql
echo ")"                                           >> table.sql
echo "WITH ("                                      >> table.sql
echo " OIDS=FALSE"                                 >> table.sql
echo ");"                                          >> table.sql
echo "ALTER TABLE datomic_kvs"                     >> table.sql
echo " OWNER TO postgres;"                         >> table.sql
echo "GRANT ALL ON TABLE datomic_kvs TO postgres;" >> table.sql
echo "GRANT ALL ON TABLE datomic_kvs TO public;"   >> table.sql

psql -f table.sql -U postgres -d datomic
rm table.sql

echo "-- DROP ROLE :datomic"                         > user.sql
echo ""                                              >> user.sql
echo "CREATE ROLE datomic LOGIN PASSWORD 'datomic';" >> user.sql

psql -f user.sql -U postgres -d datomic
rm user.sql
