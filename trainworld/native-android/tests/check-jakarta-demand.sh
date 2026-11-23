#!/usr/bin/env bash
# Compare all five periods with an independently built web module on the same packed services.
set -euo pipefail
if [[ $# != 4 ]]; then
    echo "Usage: bash check-jakarta-demand.sh original-sim.js city-pack-dir index.json.gz save-dir" >&2
    exit 2
fi
absolute() { realpath "$1"; }
module=$(absolute "$1")
pack=$(absolute "$2")
index=$(absolute "$3")
saves=$(absolute "$4")
cd "$(dirname "$0")/.."
results=$(mktemp -d)
trap 'rm -rf "$results"' EXIT
for scenario in jak_krl jak-2; do
    for period in 0 1 2 3 4; do
        echo "Comparing $scenario, period $period"
        WORLD_CITY=jak WORLD_CITY_DATA="$pack" WORLD_MARKET_INDEX="$index" \
        WORLD_SAVE="$saves/$scenario.save" WORLD_PERIOD="$period" \
        WORLD_NETWORK_EXPORT="$results/service.json" WORLD_RESULT_EXPORT="$results/result.json" \
            cargo test --locked --release --lib city_commute -- --ignored --nocapture
        node check-ridership.mjs "$module" "$pack/jak.json" "$pack/jak.bin" \
            "$results/service.json" "$results/result.json" "$index" \
            "$pack/market-drives.bin" "$pack/jak.market.json"
    done
done
