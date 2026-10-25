/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.sim;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Server-side model of a thrown ender pearl ({@code ThrowableProjectile}).
 * Each tick applies gravity, then drag, then sweeps along the new velocity.
 */
public final class PearlSim
{
	public static final double GRAVITY = 0.03;
	public static final float DRAG = 0.99F;
	
	public Vec3 pos;
	public Vec3 vel;
	
	/** Completed moves, i.e. the entity's {@code tickCount}. */
	public int age;
	
	public PearlSim(Vec3 pos, Vec3 vel, int age)
	{
		this.pos = pos;
		this.vel = vel;
		this.age = age;
	}
	
	public PearlSim copy()
	{
		return new PearlSim(pos, vel, age);
	}
	
	/** Where a player at {@code feet} would spawn an ender pearl. */
	public static Vec3 spawnPos(Vec3 feet)
	{
		return feet.add(0, PlayerSim.EYE_HEIGHT + Shots.PEARL_SPAWN_OFFSET, 0);
	}
	
	/**
	 * Applies gravity and drag for the next move.
	 *
	 * @return where the pearl would end up if it hits nothing
	 */
	public Vec3 accelerate()
	{
		vel = new Vec3(vel.x, vel.y - GRAVITY, vel.z).scale(DRAG);
		return pos.add(vel);
	}
	
	/** Finishes the move started by {@link #accelerate()}. */
	public void finishMove(Vec3 to)
	{
		pos = to;
		age++;
	}
	
	/** Advances one full tick, ignoring anything it could hit. */
	public void stepFree()
	{
		finishMove(accelerate());
	}
	
	/**
	 * @return where the pearl hits a block on its way to {@code to}, or
	 *         {@code null}
	 */
	public Vec3 clipBlocks(Level level, Vec3 to)
	{
		BlockHitResult hit =
			level.clip(new ClipContext(pos, to, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS ? null : hit.getLocation();
	}
}
