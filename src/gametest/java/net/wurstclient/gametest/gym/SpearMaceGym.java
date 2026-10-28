/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * A gym where SpearMace fights itself, to collect data for learned
 * policies. Two bot clients fight in an arena of the host's world, with
 * planner configs sampled per episode, while anyone else watches.
 *
 * <pre>
 * ./gradlew runSpearMaceGym
 * </pre>
 *
 * Then, in any singleplayer world:
 * <ul>
 * <li>{@code /gym arena <from> <to>}: the box the fight must stay in
 * <li>{@code /gym spawn add [pos]}, {@code /gym spawn clear}: start points;
 * with fewer than two, starts are sampled on the arena's floor
 * <li>{@code /gym kit}, {@code /gym kit default}: hand out your inventory
 * instead of the SpearMaceTest kit
 * <li>{@code /gym start [episodes] [a=<cfg>] [b=<cfg>] [mirror] [self]
 * [noagents] [seed=N] [hours=H] [ticks=N] [health=N]}: opens the world to LAN
 * and
 * starts two bot clients, Gym-A and Gym-B (with {@code self}, you are A).
 * Configs are {@code rules}, {@code beam:WxD}, {@code mcts:N} or
 * {@code random} (default).
 * <li>{@code /gym stop}, {@code /gym status}
 * </ul>
 *
 * {@code WURST_GYM_AUTOSTART="<start options>"} does all of this in a
 * flat world without anyone at the keyboard, see {@link GymAutostart}.
 *
 * <p>
 * Data goes to {@code spearmace-gym/<time>/} in the project (or
 * {@code WURST_GYM_DIR}): {@code episodes.jsonl} from the referee and
 * {@code decisions-A.jsonl}/{@code decisions-B.jsonl} from the bots.
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
		
		int port = Integer.getInteger("wurst.spearmace.gym.port", DEFAULT_PORT);
		GymPayloads.register();
		GymAgent agent = new GymAgent();
		agent.register();
		
		switch(role)
		{
			case "host" -> registerHost(port);
			case "agent" -> agent.connectTo(port);
			default -> throw new IllegalArgumentException(
				"wurst.spearmace.gym must be host or agent, not " + role);
		}
	}
	
	private static void registerHost(int port)
	{
		GymCommand command = new GymCommand(port);
		GymAutostart.registerIfRequested(() -> command.referee() != null);
		CommandRegistrationCallback.EVENT.register((dispatcher, registries,
			selection) -> command.register(dispatcher));
		
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			GymReferee r = command.referee();
			if(r != null && r.server() == server)
				r.tick();
		});
		ServerLivingEntityEvents.ALLOW_DEATH
			.register((entity, source, amount) -> {
				GymReferee r = command.referee();
				return r == null || r.allowDeath(entity, source);
			});
		ServerLivingEntityEvents.AFTER_DAMAGE
			.register((entity, source, baseDamage, damageTaken, blocked) -> {
				GymReferee r = command.referee();
				if(r != null)
					r.afterDamage(entity, source, baseDamage, damageTaken,
						blocked);
			});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			GymReferee r = command.referee();
			if(r != null && r.server() == server)
				r.finish("world closed");
		});
	}
}
