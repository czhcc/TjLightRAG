#!/usr/bin/env bash
set -euo pipefail
# Git records executable bits, not read/search permissions under a restrictive
# checkout umask. Copy the read-only source as root; upstream then drops to postgres.
install -d -m 0755 /docker-entrypoint-initdb.d
install -m 0755 /opt/tjlightrag-init/01-runtime-user.sh /docker-entrypoint-initdb.d/01-runtime-user.sh
exec /usr/local/bin/docker-entrypoint.sh "$@"
