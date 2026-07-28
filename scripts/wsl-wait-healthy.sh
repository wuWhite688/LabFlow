#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJ="${LABFLOW_WSL_PROJ:-$(cd -- "$SCRIPT_DIR/.." && pwd)}"

export HTTP_PROXY="${HTTP_PROXY:-http://127.0.0.1:7897}"
export HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:7897}"
export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1}"

if ! docker info >/dev/null 2>&1; then
  nohup env HTTP_PROXY="$HTTP_PROXY" HTTPS_PROXY="$HTTPS_PROXY" NO_PROXY="$NO_PROXY" dockerd >/tmp/dockerd.log 2>&1 &
  sleep 4
fi

cd "$PROJ"

docker compose --env-file .env up -d

for i in $(seq 1 60); do
  m=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' labflow-mysql 2>/dev/null || echo missing)
  r=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' labflow-redis 2>/dev/null || echo missing)
  q=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' labflow-rabbitmq 2>/dev/null || echo missing)
  echo "t=${i} mysql=${m} redis=${r} rabbit=${q}"
  if [[ "$m" == "healthy" && "$r" == "healthy" && "$q" == "healthy" ]]; then
    echo ALL_HEALTHY
    docker compose ps
    exit 0
  fi
  sleep 3
done

echo "TIMEOUT waiting for healthy services"
docker compose ps
docker logs --tail 40 labflow-mysql || true
exit 1
