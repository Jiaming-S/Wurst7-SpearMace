#!/usr/bin/env bash
# Starts the SpearMace gym server with the settings from docker-compose.yml.
#
# Every MC_<KEY> variable sets server.properties' <key>, lowercased with _ as -
# (MC_VIEW_DISTANCE=8 -> view-distance=8), on top of
# src/gametest/gym-server/server.properties. WURST_GYM_* go to the gym as is.
set -euo pipefail
cd /wurst

RUN_DIR=build/run/spearMaceGymServer
mkdir -p "$RUN_DIR"

if [ "${WURST_GYM_ACCEPT_EULA:-}" != "true" ]; then
	echo "Accept the Minecraft EULA (https://aka.ms/MinecraftEULA) first:" >&2
	echo "  set WURST_GYM_ACCEPT_EULA=true in docker-compose.yml" >&2
	exit 1
fi
echo "eula=true" > "$RUN_DIR/eula.txt"

props="$RUN_DIR/server.properties"
cp src/gametest/gym-server/server.properties "$props"
while IFS='=' read -r name value; do
	key=$(echo "${name#MC_}" | tr 'A-Z_' 'a-z-')
	value=${value//\\/\\\\}
	value=${value//:/\\:}
	if grep -q "^$key=" "$props"; then
		sed -i "s|^$key=.*|$key=${value//|/\\|}|" "$props"
	else
		echo "$key=$value" >> "$props"
	fi
	echo "server.properties: $key=$value"
done < <(env | grep '^MC_[A-Z0-9_]*=' || true)

# Unset ones would reach the gym as empty strings
for var in WURST_GYM_AUTOSTART WURST_GYM_OPS WURST_GYM_HEADLESS; do
	[ -z "${!var:-}" ] && unset "$var"
done

# The server reads its console from a FIFO that both docker attach and
# docker stop write to: SIGTERM becomes /stop, which saves the world and stops
# the bots. Killing Gradle would kill the server without either.
console=$(mktemp -u)
mkfifo "$console"
# prepareSpearMaceGymServer would copy server.properties over the one above
./gradlew --no-daemon --offline runSpearMaceGymServer \
	-x prepareSpearMaceGymServer "$@" < "$console" &
gradle=$!
exec 3> "$console"
rm "$console"
cat <&0 >&3 &

stop_server() {
	echo "Stopping the gym server"
	echo stop >&3
}
trap stop_server TERM INT
# wait returns early when a trapped signal arrives
while kill -0 "$gradle" 2>/dev/null; do
	wait "$gradle" && status=0 || status=$?
done
exit "${status:-0}"
