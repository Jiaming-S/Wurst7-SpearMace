/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * The server half of the SpearMace gym: the {@code /gym} command and its
 * referees, on a dedicated server ({@code -Dwurst.spearmace.gym=server})
 * or in a client's singleplayer world ({@code host}). Wurst isn't loaded on
 * a dedicated server, so nothing here may use it. See {@link SpearMaceGym}
 * for the client half.
 *
 * <p>
 * On a dedicated server, {@code WURST_GYM_AUTOSTART="<start options>"}
 * runs {@code /gym start} once the server is up and stops the server when
 * the gym is done.
 */
public final class SpearMaceGymServer implements ModInitializer
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("SpearMaceGym");
	
	private static final int QUIT_DELAY_TICKS = 100;
	
	/** How a dedicated server gets ready for bots: it already is. */
	private static GymCommand.Host host = MinecraftServer::getPort;
	private static GymCommand command;
	
	@Override
	public void onInitialize()
	{
		String role = System.getProperty("wurst.spearmace.gym");
		if(role == null)
			return;
		
		GymPayloads.register();
		if(!role.equals("host") && !role.equals("server"))
			return;
		
		command = new GymCommand(server -> host.prepare(server));
		CommandRegistrationCallback.EVENT.register((dispatcher, registries,
			selection) -> command.register(dispatcher));
		
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			GymRun run = command.run();
			if(run != null && run.server() == server)
				run.tick();
		});
		ServerLivingEntityEvents.ALLOW_DEATH
			.register((entity, source, amount) -> {
				GymRun run = command.run();
				return run == null || run.allowDeath(entity, source);
			});
		ServerLivingEntityEvents.AFTER_DAMAGE
			.register((entity, source, baseDamage, damageTaken, blocked) -> {
				GymRun run = command.run();
				if(run != null)
					run.afterDamage(entity, source, baseDamage, damageTaken,
						blocked);
			});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			GymRun run = command.run();
			if(run != null && run.server() == server)
				run.finish("world closed");
		});
		
		String autostart = System.getenv("WURST_GYM_AUTOSTART");
		if(role.equals("server") && autostart != null)
			registerAutostart(autostart.trim());
	}
	
	private static void registerAutostart(String options)
	{
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			String start = ("gym start " + options).trim();
			LOGGER.info("Running /{}", start);
			server.getCommands().performPrefixedCommand(
				server.createCommandSourceStack(), start);
			if(command.run() == null)
			{
				LOGGER.error("/gym start failed, stopping");
				server.halt(false);
			}
		});
		
		// lets the bots get their last Stop and close their files first
		int[] doneTicks = {0};
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if(command.run() != null || ++doneTicks[0] != QUIT_DELAY_TICKS)
				return;
			LOGGER.info("Gym done, stopping the server");
			server.halt(false);
		});
	}
	
	/** Lets the client open its singleplayer world to the bots first. */
	public static void setHost(GymCommand.Host host)
	{
		SpearMaceGymServer.host = host;
	}
	
	/** The {@code /gym} command, or {@code null} unless this hosts it. */
	public static GymCommand command()
	{
		return command;
	}
}
