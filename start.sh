#!/bin/sh
#cd /opt/docker/onelike-backend
#mvn clean install -DskipTests
systemctl stop cron
docker rm -f onelike nginx_server redis redis-master redis-replica redis-sentinel
docker rmi -f onelike-backend nginx maven mritd/openjdk17 redis
docker-compose up -d --wait
systemctl start cron
#cd /opt/docker/nginx-onelike
#docker build -t nginx-reverse-proxy .
#docker exec nginx-base nginx-reverse-proxy -s reload
#docker run -d --name nginx-base -p 80:80 nginx-reverse-proxy