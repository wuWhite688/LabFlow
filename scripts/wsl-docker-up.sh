#!/usr/bin/env bash
# One-shot helper: ensure dockerd + compose stack. Persistent lifecycle is owned by
# Windows start-middleware.ps1 keepalive process (do not rely on nohup alone).
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJ="${LABFLOW_WSL_PROJ:-$(cd -- "$SCRIPT_DIR/.." && pwd)}"
cd "$PROJ"

export HTTP_PROXY="${HTTP_PROXY:-http://127.0.0.1:7897}"
export HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:7897}"
export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1}"

echo "Using proxy $HTTP_PROXY"

if ! docker info >/dev/null 2>&1; then
  echo "Starting dockerd..."
  nohup env HTTP_PROXY="$HTTP_PROXY" HTTPS_PROXY="$HTTPS_PROXY" NO_PROXY="$NO_PROXY" dockerd >>/tmp/labflow-dockerd.log 2>&1 &
  for i in $(seq 1 30); do
    if docker info >/dev/null 2>&1; then
      echo "dockerd_ready after ${i}s"
      break
    fi
    sleep 1
  done
fi

if ! docker info >/dev/null 2>&1; then
  echo "dockerd failed to start"
  tail -50 /tmp/labflow-dockerd.log || true
  exit 1
fi

echo "Pulling/starting compose stack..."
docker compose --env-file .env up -d
docker compose ps
