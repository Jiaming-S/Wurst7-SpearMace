/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Runs the gym without anyone at the keyboard: opens (or first creates) a
 * flat world called {@value #WORLD}, types {@code /gym start} with the
 * options from {@code WURST_GYM_AUTOSTART} and quits when the gym is done,
 * e.g.
 *
 * <pre>
 * WURST_GYM_AUTOSTART="100 a=rules b=beam:4x3" ./gradlew runSpearMaceGym
 * </pre>
 */
public final class GymAutostart
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("SpearMaceGym");
	private static final String WORLD = "spearmace-gym";
	
	/** Lets the world load before the gym starts. */
	private static final int SETTLE_TICKS = 40;
	private static final int QUIT_DELAY_TICKS = 100;
	
	private final String options;
	private final BooleanSupplier gymRunning;
	private Stage stage = Stage.TITLE;
	private int ticks;
	
	private enum Stage
	{
		TITLE,
		CREATING,
		LOADING,
		STARTING,
		RUNNING,
		DONE;
	}
	
	private GymAutostart(String options, BooleanSupplier gymRunning)
	{
		this.options = options;
		this.gymRunning = gymRunning;
	}
	
	public static void registerIfRequested(BooleanSupplier gymRunning)
	{
		String options = System.getenv("WURST_GYM_AUTOSTART");
		if(options == null)
			return;
		
		GymAutostart autostart = new GymAutostart(options.trim(), gymRunning);
		ClientTickEvents.END_CLIENT_TICK.register(autostart::tick);
	}
	
	private void tick(Minecraft mc)
	{
		switch(stage)
		{
			case TITLE ->
			{
				if(!(mc.gui.screen() instanceof TitleScreen))
					return;
				if(mc.getLevelSource().levelExists(WORLD))
				{
					LOGGER.info("Opening world {}", WORLD);
					mc.createWorldOpenFlows().openWorld(WORLD, () -> {});
					stage = Stage.LOADING;
				}else
				{
					LOGGER.info("Creating world {}", WORLD);
					CreateWorldScreen.openFresh(mc, () -> {});
					stage = Stage.CREATING;
				}
			}
			case CREATING ->
			{
				if(!(mc.gui.screen() instanceof CreateWorldScreen screen))
					return;
				configure(screen.getUiState());
				AbstractButton create =
					findButton(screen, "selectWorld.create");
				if(create == null)
					throw new IllegalStateException(
						"No create button on the create world screen");
				create.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
				stage = Stage.LOADING;
			}
			case LOADING ->
			{
				if(mc.player == null || mc.getSingleplayerServer() == null)
				{
					ticks = 0;
					return;
				}
				if(++ticks < SETTLE_TICKS)
					return;
				
				String command = ("gym start " + options).trim();
				LOGGER.info("Running /{}", command);
				mc.player.connection.sendCommand(command);
				stage = Stage.STARTING;
				ticks = 0;
			}
			case STARTING ->
			{
				if(gymRunning.getAsBoolean())
				{
					stage = Stage.RUNNING;
					ticks = 0;
				}else if(++ticks > SETTLE_TICKS)
				{
					LOGGER.error("/gym start failed, see the chat");
					stage = Stage.DONE;
				}
			}
			case RUNNING ->
			{
				if(gymRunning.getAsBoolean())
				{
					ticks = 0;
					return;
				}
				// lets the bot clients close their files first
				if(++ticks < QUIT_DELAY_TICKS)
					return;
				LOGGER.info("Gym done, quitting");
				stage = Stage.DONE;
				mc.stop();
			}
			default ->
				{
				}
		}
	}
	
	private static void configure(WorldCreationUiState ui)
	{
		ui.setName(WORLD);
		ui.setGameMode(SelectedGameMode.CREATIVE);
		ui.setAllowCommands(true);
		ui.setSeed("1");
		ui.setWorldType(new WorldCreationUiState.WorldTypeEntry(ui.getSettings()
			.worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(WorldPresets.FLAT)));
		GameRules rules = ui.getGameRules();
		rules.set(GameRules.SPAWN_MOBS, false, null);
		rules.set(GameRules.ADVANCE_TIME, false, null);
		rules.set(GameRules.ADVANCE_WEATHER, false, null);
	}
	
	private static AbstractButton findButton(ContainerEventHandler parent,
		String translationKey)
	{
		for(GuiEventListener child : parent.children())
		{
			if(child instanceof AbstractButton button
				&& button.getMessage()
					.getContents() instanceof TranslatableContents t
				&& t.getKey().equals(translationKey))
				return button;
			
			if(child instanceof ContainerEventHandler container)
			{
				AbstractButton found = findButton(container, translationKey);
				if(found != null)
					return found;
			}
		}
		return null;
	}
}
