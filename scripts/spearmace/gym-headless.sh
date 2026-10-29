#!/usr/bin/env bash
# Runs the SpearMace self-play gym on a dedicated server, with its bot
# clients on the same machine. Works without a display: on Linux the bots
# use SDL's offscreen video driver and Mesa's software Vulkan driver
# (apt-get install mesa-vulkan-drivers); if they can't open a window, run
# this under xvfb-run instead.
#
# Usage: scripts/spearmace/gym-headless.sh [start options]
# Default options collect expert labels for distillation. See
# net.wurstclient.gametest.gym.SpearMaceGym for all of them. Data goes to
# spearmace-gym/<time>/ (or WURST_GYM_DIR). The server stops when the gym
# is done; to start the gym by hand instead, use gym-server.sh.
#
# The server only starts after you accept the Minecraft EULA
# (https://aka.ms/MinecraftEULA), e.g. with WURST_GYM_ACCEPT_EULA=true.
set -euo pipefail

OPTIONS="${*:-hours=12 pairs=4 tps=4 health=100 act=beam:4x3 expert=mcts:4000 explore=0.1}"
WURST_GYM_AUTOSTART="$OPTIONS" exec "$(dirname "$0")/gym-server.sh"
