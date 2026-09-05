#!/bin/bash

# Check Redis Master Status
MASTER_STATUS=$(docker exec redis-sentinel redis-cli -p 26379 sentinel get-master-addr-by-name mymaster)

if [[ $MASTER_STATUS == *"nil"* ]]; then
  echo "Master is down! Triggering failover..."
  docker exec redis-sentinel redis-cli -p 26379 sentinel failover mymaster
else
  echo "Master is healthy: $MASTER_STATUS"
fi