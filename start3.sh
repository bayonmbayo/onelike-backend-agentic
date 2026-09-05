#!/bin/sh
cd /opt/docker/onelike-backend
mvn clean install -DskipTests
systemctl stop cron
docker rm -f onelike nginx_server redis redis-master redis-replica redis-sentinel
docker rmi -f onelike-backend nginx maven mritd/openjdk17 redis
docker-compose -f docker-compose2.yml up -d
systemctl start cron