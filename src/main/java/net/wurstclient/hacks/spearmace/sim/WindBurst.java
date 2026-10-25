/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.sim;

import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Knockback that a wind charge explosion gives the player, copied from
 * {@code ServerExplosion.hurtEntities()}.
 */
public enum WindBurst
{
	;
	
	public static final float RADIUS = 1.2F;
	public static final float KNOCKBACK_MULTIPLIER = 1.22F;
	
	/**
	 * @param feet
	 *            the player's position, as the server knows it when the
	 *            charge explodes
	 * @return the velocity added to the player, or {@link Vec3#ZERO} if the
	 *         player is out of range
	 */
	public static Vec3 knockback(Level level, Vec3 center, Vec3 feet)
	{
		// distance is measured to the feet, direction to the eyes
		double dist = Math.sqrt(feet.distanceToSqr(center)) / (RADIUS * 2.0F);
		if(dist > 1)
			return Vec3.ZERO;
		
		Vec3 eyes = feet.add(0, PlayerSim.EYE_HEIGHT, 0);
		Vec3 direction = eyes.subtract(center).normalize();
		float exposure = getSeenPercent(level, center, PlayerSim.box(feet));
		double power = (1.0 - dist) * exposure * KNOCKBACK_MULTIPLIER;
		return direction.scale(power);
	}
	
	/**
	 * Same as {@code ServerExplosion.getSeenPercent()}, but for a box instead
	 * of an entity, so it works for predicted positions.
	 */
	public static float getSeenPercent(Level level, Vec3 center, AABB bb)
	{
		double xs = 1.0 / ((bb.maxX - bb.minX) * 2.0 + 1.0);
		double ys = 1.0 / ((bb.maxY - bb.minY) * 2.0 + 1.0);
		double zs = 1.0 / ((bb.maxZ - bb.minZ) * 2.0 + 1.0);
		double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
		double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
		
		int hits = 0;
		int count = 0;
		for(double xx = 0; xx <= 1; xx += xs)
			for(double yy = 0; yy <= 1; yy += ys)
				for(double zz = 0; zz <= 1; zz += zs)
				{
					double x = Mth.lerp(xx, bb.minX, bb.maxX);
					double y = Mth.lerp(yy, bb.minY, bb.maxY);
					double z = Mth.lerp(zz, bb.minZ, bb.maxZ);
					Vec3 from = new Vec3(x + xOffset, y, z + zOffset);
					if(level
						.clip(new ClipContext(from, center,
							ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
							CollisionContext.empty()))
						.getType() == HitResult.Type.MISS)
						hits++;
					count++;
				}
			
		return (float)hits / count;
	}
}
