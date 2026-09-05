#!/bin/sh
cd /opt/jenkins/onelike-backend-jx
export MAVEN_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED"
mvn clean install -DskipTests
# docker exec redis-master redis-cli shutdown
# docker exec redis-replica redis-cli shutdown
# docker exec redis-sentinel redis-cli -p 26379 shutdown
# sleep 2
# sudo systemctl stop cron
docker rm -f onelike nginx_server redis redis-master redis-replica redis-sentinel mysqldb
docker rmi -f onelike-backend nginx maven mritd/openjdk17 redis
docker build -t onelike-backend .
# systemctl disable apache2 (perma)
# systemctl stop apache2
docker-compose -f docker-compose3.yml up -d
# sudo systemctl start cron