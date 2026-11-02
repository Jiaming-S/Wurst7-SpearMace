/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer.MultiplayerScope;

/**
 * A gym where SpearMace fights itself, to collect data for learned
 * policies. Bot clients fight in pairs in arenas of the host's world, with
 * planner configs sampled per episode, while anyone else watches. This is
 * the client half: the bots, and opening a singleplayer world to them. See
 * {@link SpearMaceGymServer} for the referee.
 *
 * <p>
 * On a desktop, {@code ./gradlew runSpearMaceGym}, then in any
 * singleplayer world (headless: {@code ./gradlew runSpearMaceGymServer},
 * see {@code scripts/spearmace/gym-headless.sh}):
 * <ul>
 * <li>{@code /gym arena <from> <to>}: the box the fight must stay in
 * <li>{@code /gym spawn add [pos]}, {@code /gym spawn clear}: start points;
 * with fewer than two, starts are sampled on the arena's floor
 * <li>{@code /gym kit}, {@code /gym kit default}: hand out your inventory
 * instead of the SpearMaceTest kit
 * <li>{@code /gym start [episodes] [act=<cfg>] [a=<cfg>] [b=<cfg>]
 * [expert=<cfg>] [explore=P] [tps=N] [pairs=N] [mirror] [self] [noagents]
 * [seed=N] [hours=H] [ticks=N] [health=N] [apples=N] [armor] [totem]}:
 * starts a bot client per player
 * (Gym-A and Gym-B, or Gym-0A, Gym-0B, ... with {@code pairs}; with
 * {@code self}, you are the first A). Configs are {@code rules},
 * {@code beam:WxD}, {@code mcts:N}, {@code random} (default) or
 * {@code expert}. With {@code expert}, every decision is also labeled by
 * that much slower search; {@code tps} slows the game down to give it
 * time.
 * <li>{@code /gym stop}, {@code /gym status}
 * </ul>
 *
 * {@code WURST_GYM_AUTOSTART="<start options>"} does all of this in a
 * flat world without anyone at the keyboard, see {@link GymAutostart}.
 *
 * <p>
 * Data goes to {@code spearmace-gym/<time>/} in the project (or
 * {@code WURST_GYM_DIR}): {@code episodes.jsonl} from the referee and
 * {@code decisions-<side>.jsonl} from each bot.
 *
 * <p>
 * Does nothing unless started with {@code -Dwurst.spearmace.gym=host} or
 * {@code agent}, so the gametests don't see it.
 */
public final class SpearMaceGym implements ClientModInitializer
{
	public static final int DEFAULT_PORT = 25599;
	
	@Override
	public void onInitializeClient()
	{
		String role = System.getProperty("wurst.spearmace.gym");
		if(role == null)
			return;
		
		GymAgent agent = new GymAgent();
		agent.register();
		
		switch(role)
		{
			case "host" -> registerHost();
			case "agent" -> agent.connectTo(
				System.getProperty("wurst.spearmace.gym.host",
					"localhost:" + DEFAULT_PORT),
				Boolean.getBoolean("wurst.spearmace.gym.headless"));
			default -> throw new IllegalArgumentException(
				"wurst.spearmace.gym must be host or agent here, not " + role);
		}
	}
	
	private static void registerHost()
	{
		int port = Integer.getInteger("wurst.spearmace.gym.port", DEFAULT_PORT);
		SpearMaceGymServer.setHost(server -> {
			if(!(server instanceof IntegratedServer integrated)
				|| integrated.isPublished())
				return server.getPort();
			
			Minecraft.getInstance().execute(() -> integrated
				.publishServer(MultiplayerScope.LAN, false, port));
			return port;
		});
		GymAutostart.registerIfRequested(
			() -> SpearMaceGymServer.command().run() != null);
	}
}
