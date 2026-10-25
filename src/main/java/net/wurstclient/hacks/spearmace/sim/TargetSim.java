/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.sim;

import java.util.List;

import net.minecraft.world.entity.Entity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.state.TargetState;

/**
 * Rough model of another entity's motion as the client sees it: it keeps
 * its velocity and falls like a player when airborne. On the ground it keeps
 * walking the way it walks, so a target that was knocked back is expected
 * to keep sliding. One {@link #step()} is one client tick.
 *
 * <p>
 * The client shows other entities a few ticks late, interpolating toward
 * the positions the server sends. The crosshair pick uses that lagged box,
 * so the velocity comes from the last two client positions rather than
 * from the server's velocity (see {@link TargetState}).
 */
public final class TargetSim
{
	private static final Minecraft MC = WurstClient.MC;
	
	private final Level level;
	private final TargetState state;
	
	/** The entity, for block collisions that depend on it, or null. */
	private final Entity entity;
	
	public Vec3 pos;
	public Vec3 vel;
	public boolean onGround;
	
	private TargetSim(Level level, TargetState state)
	{
		this.level = level;
		this.state = state;
		entity = level.getEntity(state.entityId());
		pos = state.pos();
		vel = state.vel();
		onGround = state.onGround();
	}
	
	public static TargetSim of(Level level, TargetState state)
	{
		return new TargetSim(level, state);
	}
	
	public static TargetSim of(LivingEntity target)
	{
		return of(MC.level, TargetState.capture(target));
	}
	
	public void step()
	{
		if(!state.moves())
			return;
		
		Vec3 move = entity != null
			? Entity.collideBoundingBox(entity, vel, box(), level, List.of())
			: Entity.collideBoundingBox(CollisionContext.empty(), vel, box(),
				level, List.of());
		boolean collidedY = move.y != vel.y;
		onGround = collidedY && vel.y < 0;
		pos = pos.add(move);
		vel = new Vec3(move.x != vel.x ? 0 : vel.x, collidedY ? 0 : vel.y,
			move.z != vel.z ? 0 : vel.z);
		
		double friction = onGround ? 1 : PlayerSim.AIR_FRICTION;
		vel = new Vec3(vel.x * friction,
			(vel.y - PlayerSim.GRAVITY) * PlayerSim.VERTICAL_DRAG,
			vel.z * friction);
	}
	
	public AABB box()
	{
		return state.box(pos);
	}
	
	public Vec3 eyePos()
	{
		return pos.add(0, state.eyeHeight(), 0);
	}
}
