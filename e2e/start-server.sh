#!/usr/bin/env bash
# Starts the four server components in the background from their built jars, configured like
# sonic-server/docker-compose.yml but on this machine, with the gateway on port 3000.
# Usage: e2e/start-server.sh <log directory>; MySQL on 127.0.0.1:3306 with MYSQL_ROOT_PASSWORD.
set -euo pipefail
logs=$1
mkdir -p "$logs"

export SONIC_EUREKA_USERNAME=sonic SONIC_EUREKA_PASSWORD="$(openssl rand -hex 24)" SONIC_EUREKA_HOST=127.0.0.1 SONIC_EUREKA_PORT=8761
export MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_DATABASE=sonic MYSQL_USERNAME=root MYSQL_PASSWORD="$MYSQL_ROOT_PASSWORD"
export SONIC_SERVER_HOST=127.0.0.1 SONIC_SERVER_PORT=3000 SECRET_KEY="$(openssl rand -hex 32)" EXPIRE_DAY=14
export PERMISSION_ENABLE=true PERMISSION_SUPER_ADMIN=sonic REGISTER_ENABLE=true NORMAL_USER_ENABLE=true LDAP_USER_ENABLE=false

start() {
  nohup java -Xmx512m -jar "sonic-server/sonic-server-$1/target/sonic-server-$1.jar" \
    --eureka.instance.ip-address=127.0.0.1 > "$logs/$1.log" 2>&1 &
}

start eureka
for _ in $(seq 60); do
  curl -sf http://127.0.0.1:8761/actuator/health > /dev/null && break
  sleep 2
done
start controller
start folder
start gateway
