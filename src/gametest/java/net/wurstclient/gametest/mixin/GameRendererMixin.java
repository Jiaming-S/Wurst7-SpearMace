/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.GameRenderer;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin
{
	@Unique
	private static final boolean HEADLESS =
		Boolean.getBoolean("wurst.spearmace.gym.headless");
	
	/**
	 * Skips drawing the world in headless gym bots, which nobody watches,
	 * so that their CPU goes to the search instead. Only once the player
	 * is in the world, as the loading screen waits for the chunks around
	 * it to be drawn.
	 */
	@Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
	private void onRenderLevel(CallbackInfo ci)
	{
		ClientPacketListener connection =
			Minecraft.getInstance().getConnection();
		if(HEADLESS && connection != null && connection.hasClientLoaded())
			ci.cancel();
	}
}
