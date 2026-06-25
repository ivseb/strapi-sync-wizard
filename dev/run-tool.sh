#!/usr/bin/env bash
# Run the sync tool on the host (the Mac), pointed at the local docker harness Postgres.
# Prereqs: the DoubleStrapi harness is up (`docker compose up -d`) so port 5434 / 1337 / 1338 are live.
#
#   PORT         tool HTTP port            (default 8080)
#   DB_SALT      hex salt for instance encryption (default test value)
set -euo pipefail

cd "$(dirname "$0")/.."   # repo root

JAVA_HOME_21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
if [ -z "$JAVA_HOME_21" ]; then
  echo "JDK 21 not found (/usr/libexec/java_home -v 21). Install a JDK 21 (Gradle 9.6 runs on it)." >&2
  exit 1
fi
export JAVA_HOME="$JAVA_HOME_21"

export JDBC_DATABASE_URL="jdbc:postgresql://127.0.0.1:5434/strapisync"
export JDBC_DATABASE_USERNAME=postgres
export JDBC_DATABASE_PASSWORD=postgres
export DB_SALT="${DB_SALT:-5c0744940b5c369d}"   # must be even-length hex
export PORT="${PORT:-8080}"
export DATA_FOLDER="${DATA_FOLDER:-/tmp/strapisync-data}"
mkdir -p "$DATA_FOLDER"

echo "Starting sync tool on http://localhost:${PORT} (JAVA_HOME=$JAVA_HOME) ..."
exec ./gradlew run --console=plain
