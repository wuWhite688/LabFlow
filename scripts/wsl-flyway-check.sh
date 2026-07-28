#!/usr/bin/env bash
set -euo pipefail
# Usage: wsl-flyway-check.sh <user> <password> <database>
USER_NAME="${1:?user}"
PASS="${2:?pass}"
DB="${3:?db}"
docker exec labflow-mysql mysql -N -u"$USER_NAME" -p"$PASS" "$DB" -e \
  "SELECT CONCAT(version, '|', description, '|', success) FROM flyway_schema_history ORDER BY installed_rank;" 2>/dev/null
