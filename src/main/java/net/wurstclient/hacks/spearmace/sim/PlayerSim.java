/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.sim;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.state.SelfState;

/**
 * Tick-exact model of the local player's own movement
 * ({@code LivingEntity.aiStep()} and {@code travelInAir()}), without fluids,
 * effects or step-up. One {@link #step()} is one client tick.
 */
public final class PlayerSim
{
	private static final Minecraft MC = WurstClient.MC;
	
	public static final double GRAVITY = 0.08;
	public static final double VERTICAL_DRAG = 0.98;
	public static final double AIR_FRICTION = 0.91;
	public static final double EYE_HEIGHT = 1.62;
	public static final double HALF_WIDTH = 0.3;
	public static final double HEIGHT = 1.8;
	
	private final Level level;
	
	public Vec3 pos;
	public Vec3 vel;
	public boolean onGround;
	
	/** Movement input: the view's yaw, which the keys move relative to. */
	public float yaw;
	public Move move = Move.NONE;
	public boolean sprinting;
	public boolean jump;
	
	/** Whether an item is in use, which slows the movement keys. */
	public boolean usingItem;
	
	public PlayerSim(Level level, Vec3 pos, Vec3 vel, boolean onGround)
	{
		this.level = level;
		this.pos = pos;
		this.vel = vel;
		this.onGround = onGround;
	}
	
	/** Copies the local player, including the keys it is holding. */
	public static PlayerSim ofLocalPlayer()
	{
		PlayerSim sim = new PlayerSim(MC.level, MC.player.position(),
			MC.player.getDeltaMovement(),
			MC.player.onGround() && supported(MC.level, MC.player.position()));
		sim.yaw = MC.player.getYRot();
		sim.move = Move.ofHeldKeys();
		sim.sprinting = MC.player.isSprinting();
		sim.jump = MC.options.keyJump.isDown();
		sim.usingItem = MC.player.isUsingItem();
		return sim;
	}
	
	/** Starts from {@code state}, including the keys it is holding. */
	public static PlayerSim of(Level level, SelfState state)
	{
		PlayerSim sim =
			new PlayerSim(level, state.pos(), state.vel(), state.onGround());
		sim.yaw = state.yaw();
		sim.move = state.move();
		sim.sprinting = state.sprinting();
		sim.jump = state.jump();
		sim.usingItem = state.eating();
		return sim;
	}
	
	/**
	 * The part of the velocity change from {@code before} to {@code after},
	 * one update later, that the player's own movement doesn't explain:
	 * pushes from the server such as explosions, lunges and launches.
	 * {@code after} has the keys and the view that the move used.
	 */
	public static Vec3 push(Level level, SelfState before, SelfState after)
	{
		PlayerSim sim =
			new PlayerSim(level, before.pos(), before.vel(), before.onGround());
		sim.yaw = after.yaw();
		sim.move = after.move();
		sim.sprinting = after.sprinting();
		sim.jump = after.jump();
		sim.usingItem = before.eating() && after.eating();
		sim.step();
		return after.vel().subtract(sim.vel);
	}
	
	public PlayerSim copy()
	{
		PlayerSim sim = new PlayerSim(level, pos, vel, onGround);
		sim.yaw = yaw;
		sim.move = move;
		sim.sprinting = sprinting;
		sim.jump = jump;
		sim.usingItem = usingItem;
		return sim;
	}
	
	public void step()
	{
		// LivingEntity.aiStep(): tiny velocities are dropped
		double vx = Math.abs(vel.x) < 0.003 ? 0 : vel.x;
		double vy = Math.abs(vel.y) < 0.003 ? 0 : vel.y;
		double vz = Math.abs(vel.z) < 0.003 ? 0 : vel.z;
		vel = new Vec3(vx, vy, vz);
		
		// LocalPlayer.aiStep(): sprinting stops without forward input
		if(!move.hasForwardImpulse())
			sprinting = false;
		
		if(jump && onGround)
		{
			vel = new Vec3(vel.x, 0.42, vel.z);
			if(sprinting)
			{
				float rad = yaw * Mth.DEG_TO_RAD;
				vel = vel.add(-Mth.sin(rad) * 0.2, 0, Mth.cos(rad) * 0.2);
			}
		}
		
		float blockFriction = onGround ? getBlockFriction() : 1;
		float speed;
		if(onGround)
			speed = (sprinting ? 0.13F : 0.1F) * (0.21600002F
				/ (blockFriction * blockFriction * blockFriction));
		else
			speed = sprinting ? 0.025999999F : 0.02F;
		vel = vel.add(move.acceleration(speed, yaw, usingItem));
		
		Vec3 move = Entity.collideBoundingBox(MC.player, vel, box(pos), level,
			List.of());
		boolean collidedY = move.y != vel.y;
		onGround = collidedY && vel.y < 0;
		pos = pos.add(move);
		vel = new Vec3(move.x != vel.x ? 0 : vel.x, collidedY ? 0 : vel.y,
			move.z != vel.z ? 0 : vel.z);
		
		double friction = blockFriction * AIR_FRICTION;
		vel = new Vec3(vel.x * friction, (vel.y - GRAVITY) * VERTICAL_DRAG,
			vel.z * friction);
	}
	
	private float getBlockFriction()
	{
		BlockPos below = BlockPos.containing(pos.x, pos.y - 0.5000001, pos.z);
		return level.getBlockState(below).getBlock().getFriction();
	}
	
	public Vec3 eyePos()
	{
		return pos.add(0, EYE_HEIGHT, 0);
	}
	
	public AABB box()
	{
		return box(pos);
	}
	
	public static AABB box(Vec3 feet)
	{
		return new AABB(feet.x - HALF_WIDTH, feet.y, feet.z - HALF_WIDTH,
			feet.x + HALF_WIDTH, feet.y + HEIGHT, feet.z + HALF_WIDTH);
	}
	
	/** How far below the feet {@link #supported} looks for a block. */
	private static final double SUPPORT_DEPTH = 1e-3;
	
	/**
	 * Whether a block is right under a player standing at {@code feet}.
	 */
	public static boolean supported(Level level, Vec3 feet)
	{
		AABB slab = new AABB(feet.x - HALF_WIDTH, feet.y - SUPPORT_DEPTH,
			feet.z - HALF_WIDTH, feet.x + HALF_WIDTH, feet.y,
			feet.z + HALF_WIDTH);
		return !level.noCollision(slab);
	}
}
