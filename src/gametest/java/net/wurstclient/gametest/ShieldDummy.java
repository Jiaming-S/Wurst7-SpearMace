/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A server-side player that holds up a shield. Only players can have their
 * shield disabled by an axe, so mobs can't stand in for this.
 *
 * <p>
 * Fake players aren't ticked like real ones, so the shield's use timer is
 * set directly to make it count as raised.
 */
public final class ShieldDummy extends FakePlayer
{
	private ShieldDummy(ServerLevel level, GameProfile profile)
	{
		super(level, profile);
	}
	
	public static ShieldDummy spawn(MinecraftServer server, double x, double y,
		double z, float yaw, double health)
	{
		ServerLevel level = server.overworld();
		ShieldDummy dummy = new ShieldDummy(level,
			new GameProfile(UUID.randomUUID(), "ShieldDummy"));
		dummy.snapTo(x, y, z, yaw, 0);
		dummy.setYHeadRot(yaw);
		dummy.setItemInHand(InteractionHand.OFF_HAND,
			new ItemStack(Items.SHIELD));
		dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
		dummy.setHealth((float)health);
		
		// the client only spawns players it has player info for
		server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket
			.createPlayerInitializing(List.of(dummy)));
		level.addFreshEntity(dummy);
		dummy.raiseShield();
		return dummy;
	}
	
	public void raiseShield()
	{
		startUsingItem(InteractionHand.OFF_HAND);
		useItemRemaining = getUseItem().getUseDuration(this) - 20;
	}
	
	public void remove(MinecraftServer server)
	{
		discard();
		server.getPlayerList().broadcastAll(
			new ClientboundPlayerInfoRemovePacket(List.of(getUUID())));
	}
	
	@Override
	public boolean isInvulnerableTo(ServerLevel level, DamageSource source)
	{
		return false;
	}
}
