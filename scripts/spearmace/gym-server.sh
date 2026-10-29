#!/usr/bin/env bash
# Runs the SpearMace gym's dedicated server and waits: join it (port 25599,
# offline mode) and run /gym yourself. Works without a display, see
# gym-headless.sh, which also starts the gym right away.
#
# Usage: scripts/spearmace/gym-server.sh [players to op...]
# The players to op default to WURST_GYM_OPS, or NotAProPro. They log in offline,
# so any client with that username gets op.
#
# The server only starts after you accept the Minecraft EULA
# (https://aka.ms/MinecraftEULA), e.g. with WURST_GYM_ACCEPT_EULA=true.
set -euo pipefail
cd "$(dirname "$0")/../.."

RUN_DIR=build/run/spearMaceGymServer

mkdir -p "$RUN_DIR"
if ! grep -qs "eula=true" "$RUN_DIR/eula.txt"; then
	if [ "${WURST_GYM_ACCEPT_EULA:-}" = "true" ]; then
		echo "eula=true" > "$RUN_DIR/eula.txt"
	else
		echo "Accept the Minecraft EULA (https://aka.ms/MinecraftEULA) first:" >&2
		echo "  WURST_GYM_ACCEPT_EULA=true $0 $*" >&2
		exit 1
	fi
fi

if [ $# -gt 0 ]; then
	WURST_GYM_OPS="$*"
fi
export WURST_GYM_OPS="${WURST_GYM_OPS:-NotAProPro}"
exec ./gradlew runSpearMaceGymServer
