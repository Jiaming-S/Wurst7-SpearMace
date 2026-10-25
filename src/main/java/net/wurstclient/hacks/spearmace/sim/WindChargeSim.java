/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.sim;

import java.util.Optional;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Server-side model of a player's wind charge
 * ({@code AbstractHurtingProjectile}
 * with no acceleration, no drag and no gravity). One {@link #step} is one
 * server tick.
 */
public final class WindChargeSim
{
	public static final double HALF_WIDTH = 0.3125 / 2;
	public static final double HEIGHT = 0.3125;
	public static final double BOX_OFFSET = 0.15;
	
	public Vec3 pos;
	public final Vec3 vel;
	
	/** Completed ticks, i.e. the entity's {@code tickCount}. */
	public int age;
	
	public WindChargeSim(Vec3 pos, Vec3 vel)
	{
		this.pos = pos;
		this.vel = vel;
	}
	
	/** Where a player at {@code feet} would spawn a wind charge. */
	public static Vec3 spawnPos(Vec3 feet)
	{
		return feet.add(0, PlayerSim.EYE_HEIGHT, 0);
	}
	
	public AABB box()
	{
		double minY = pos.y - BOX_OFFSET;
		return new AABB(pos.x - HALF_WIDTH, minY, pos.z - HALF_WIDTH,
			pos.x + HALF_WIDTH, minY + HEIGHT, pos.z + HALF_WIDTH);
	}
	
	/**
	 * Same as {@code ProjectileUtil.computeMargin()}: how much entity boxes
	 * are inflated when this projectile sweeps against them.
	 */
	public static double margin(int tickCount)
	{
		return Math.max(0, Math.min(0.3F, (tickCount - 2) / 20.0F));
	}
	
	/**
	 * Moves the charge by one tick.
	 *
	 * @param player
	 *            the box of a player the charge can hit, or {@code null}
	 * @return the explosion center if the charge hit something this tick,
	 *         otherwise {@code null}
	 */
	public Vec3 step(Level level, AABB player)
	{
		Vec3 to = pos.add(vel);
		Vec3 explosion = null;
		
		BlockHitResult blockHit =
			level.clip(new ClipContext(pos, to, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, CollisionContext.empty()));
		if(blockHit.getType() != HitResult.Type.MISS)
		{
			to = blockHit.getLocation();
			explosion = to.add(
				Vec3.atLowerCornerOf(blockHit.getDirection().getUnitVec3i())
					.scale(0.25));
		}
		
		if(player != null)
		{
			Optional<Vec3> entityHit =
				player.inflate(margin(age)).clip(pos, to);
			if(entityHit.isPresent())
			{
				to = entityHit.get();
				explosion = to;
			}
		}
		
		pos = to;
		age++;
		return explosion;
	}
}
