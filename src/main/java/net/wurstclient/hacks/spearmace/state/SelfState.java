/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.state;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.state.Pending.PearlInFlight;
import net.wurstclient.hacks.spearmace.state.Pending.WindChargeInFlight;

/**
 * Everything the models need to know about the local player, detached
 * from the game so that it can describe a predicted state as well as the
 * current one.
 *
 * @param tick
 *            the player's {@code tickCount}
 * @param forward
 *            whether the forward key is held, see {@link PlayerSim}
 * @param jump
 *            whether the jump key is held
 * @param missTime
 *            {@code Minecraft.missTime}, which blocks attack clicks
 * @param interactionRange
 *            {@code Player.entityInteractionRange()}
 * @param knockbackResistance
 *            the {@code EXPLOSION_KNOCKBACK_RESISTANCE} attribute
 */
public record SelfState(int tick, Vec3 pos, Vec3 vel, boolean onGround,
	double fallDistance, float yaw, float pitch, boolean forward,
	boolean sprinting, boolean jump, boolean fallFlying,
	int attackStrengthTicker, int missTime, double interactionRange,
	double knockbackResistance, Kit kit, List<Pending> pending)
{
	private static final Minecraft MC = WurstClient.MC;
	
	public SelfState
	{
		pending = List.copyOf(pending);
	}
	
	public static SelfState capture()
	{
		return new SelfState(MC.player.tickCount, MC.player.position(),
			MC.player.getDeltaMovement(), MC.player.onGround(),
			MC.player.fallDistance, MC.player.getYRot(), MC.player.getXRot(),
			MC.options.keyUp.isDown(), MC.player.isSprinting(),
			MC.options.keyJump.isDown(), MC.player.isFallFlying(),
			MC.player.attackStrengthTicker, MC.missTime,
			MC.player.entityInteractionRange(),
			MC.player
				.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE),
			Kit.capture(), ownProjectiles());
	}
	
	private static List<Pending> ownProjectiles()
	{
		ArrayList<Pending> pending = new ArrayList<>();
		for(Entity e : MC.level.entitiesForRendering())
		{
			if(e.isRemoved())
				continue;
			
			if(e instanceof WindCharge c && c.getOwner() == MC.player)
				pending.add(new WindChargeInFlight(c.getId(), c.position(),
					c.getDeltaMovement(), c.tickCount));
			else if(e instanceof ThrownEnderpearl p
				&& p.getOwner() == MC.player)
				pending.add(new PearlInFlight(p.getId(), p.position(),
					p.getDeltaMovement(), p.tickCount));
		}
		return pending;
	}
	
	public Vec3 eyePos()
	{
		return pos.add(0, PlayerSim.EYE_HEIGHT, 0);
	}
	
	public AABB box()
	{
		return PlayerSim.box(pos);
	}
	
	public <T extends Pending> List<T> pending(Class<T> type)
	{
		return pending.stream().filter(type::isInstance).map(type::cast)
			.toList();
	}
}
