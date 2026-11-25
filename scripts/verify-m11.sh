#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

python=$(command -v "${GECKO_M11_PYTHON:-python}")
port=${GECKO_M11_PORT:-18080}
output=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/gecko-m11.XXXXXX")
printf 'M11 verification output: %s\n' "$output"

shopt -s nullglob
api_jars=("$root"/src/modules/gecko-rest-api/target/gecko-rest-api-*.jar)
if (( ${#api_jars[@]} != 1 )); then
    printf 'Build one REST API JAR before running M11 verification.\n' >&2
    exit 1
fi

"$python" -m pytest -q tools/optimize tools/surrogate tools/fmu

export GECKO_API_BASE_URL="http://127.0.0.1:$port/gecko"
if curl -sS --max-time 2 "$GECKO_API_BASE_URL/api-docs" >/dev/null 2>&1; then
    printf 'Port %s already serves the REST API.\n' "$port" >&2
    exit 1
fi

mkdir "$output/api"
(
    cd "$output/api"
    exec java -Xmx5500m -jar "${api_jars[0]}" --server.address=127.0.0.1 \
        "--server.port=$port" --gecko.api.auth-enabled=false \
        --gecko.jobs.retained=2250 --gecko.jobs.batches=220 \
        --logging.level.root=WARN --logging.level.org.springframework.security=WARN \
        --gecko.api.rate-limit=24000 --gecko.api.rate-limit-simulation=12000 \
        --logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=OFF
) > "$output/api.log" 2>&1 &
api_pid=$!

stop_api() {
    if [[ -n $api_pid ]]; then
        kill "$api_pid" 2>/dev/null || true
        wait "$api_pid" 2>/dev/null || true
        api_pid=
    fi
}
trap stop_api EXIT

for ((attempt = 0; attempt < 120; attempt++)); do
    if ! kill -0 "$api_pid" 2>/dev/null; then
        break
    fi
    if curl -fsS --max-time 3 "$GECKO_API_BASE_URL/api-docs" > "$output/openapi.json" 2>/dev/null; then
        break
    fi
    sleep 1
done
if [[ ! -s $output/openapi.json ]]; then
    tail -50 "$output/api.log" >&2
    exit 1
fi

GECKO_SELECT_TEST_API=1 "$python" tools/select/acceptance.py
"$python" -u tools/optimize/acceptance.py --output "$output/optimizer"
"$python" -u tools/surrogate/acceptance.py --output "$output/surrogate"
stop_api

"$python" -u tools/fmu/acceptance.py --output "$output/fmu"
