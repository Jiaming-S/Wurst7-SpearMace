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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Rough model of another entity's motion as the client sees it: it keeps
 * its velocity and falls like a player when airborne. One {@link #step()}
 * is one client tick.
 *
 * <p>
 * The client shows other entities a few ticks late, interpolating toward
 * the positions the server sends. The crosshair pick uses that lagged box,
 * so the velocity comes from the last two client positions rather than
 * from the server's velocity.
 */
public final class TargetSim
{
	private final Level level;
	private final Entity entity;
	private final double halfWidth;
	private final double height;
	private final double eyeHeight;
	private final boolean moves;
	
	public Vec3 pos;
	public Vec3 vel;
	public boolean onGround;
	
	private TargetSim(Level level, Entity entity, Vec3 pos, Vec3 vel,
		boolean onGround, boolean moves)
	{
		this.level = level;
		this.entity = entity;
		halfWidth = entity.getBbWidth() / 2;
		height = entity.getBbHeight();
		eyeHeight = entity.getEyeHeight();
		this.pos = pos;
		this.vel = vel;
		this.onGround = onGround;
		this.moves = moves;
	}
	
	public static TargetSim of(LivingEntity target)
	{
		// NoAI mobs skip travel(), so they don't even fall
		boolean moves = !(target instanceof Mob mob && mob.isNoAi())
			&& !target.isNoGravity();
		Vec3 vel = target.position().subtract(target.oldPosition());
		return new TargetSim(target.level(), target, target.position(),
			moves ? vel : Vec3.ZERO, target.onGround(), moves);
	}
	
	public void step()
	{
		if(!moves)
			return;
		
		Vec3 move =
			Entity.collideBoundingBox(entity, vel, box(), level, List.of());
		boolean collidedY = move.y != vel.y;
		onGround = collidedY && vel.y < 0;
		pos = pos.add(move);
		vel = new Vec3(move.x != vel.x ? 0 : vel.x, collidedY ? 0 : vel.y,
			move.z != vel.z ? 0 : vel.z);
		
		double friction =
			onGround ? 0.6 * PlayerSim.AIR_FRICTION : PlayerSim.AIR_FRICTION;
		vel = new Vec3(vel.x * friction,
			(vel.y - PlayerSim.GRAVITY) * PlayerSim.VERTICAL_DRAG,
			vel.z * friction);
	}
	
	public AABB box()
	{
		return new AABB(pos.x - halfWidth, pos.y, pos.z - halfWidth,
			pos.x + halfWidth, pos.y + height, pos.z + halfWidth);
	}
	
	public Vec3 eyePos()
	{
		return pos.add(0, eyeHeight, 0);
	}
}
