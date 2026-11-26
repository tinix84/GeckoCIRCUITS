#!/usr/bin/env bash
set -euo pipefail

sudo apt-get update -qq
sudo apt-get install -y -qq postgresql redis-server "$@"
sudo systemctl start postgresql redis-server
sudo -u postgres psql -v ON_ERROR_STOP=1 -c "CREATE ROLE gecko LOGIN PASSWORD 'gecko_test';"
sudo -u postgres createdb -O gecko gecko_persistence_test
redis-cli ping

cat >>"${GITHUB_ENV:?Set GITHUB_ENV to the GitHub Actions environment file}" <<'ENV'
GECKO_TEST_POSTGRES=true
GECKO_TEST_REDIS=true
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost/gecko_persistence_test
SPRING_DATASOURCE_USERNAME=gecko
SPRING_DATASOURCE_PASSWORD=gecko_test
ENV
