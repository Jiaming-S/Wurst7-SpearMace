/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.sim;

import java.util.Random;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.util.Rotation;

/**
 * How thrown ender pearls and wind charges leave the player, copied from
 * {@code Projectile.shootFromRotation()} and {@code getMovementToShoot()}.
 */
public enum Shots
{
	;
	
	/** Speed of both ender pearls and wind charges, in blocks per tick. */
	public static final double POWER = 1.5;
	
	/** Spread of the per-axis triangle noise with uncertainty 1.0. */
	public static final double NOISE = 0.0172275;
	
	/** Ender pearls spawn this far below the eyes. */
	public static final double PEARL_SPAWN_OFFSET = -0.1;
	
	/**
	 * Unit direction that the server computes from the rotation in the use
	 * packet. Uses {@link Mth}'s lookup-table trigonometry, just like the
	 * server.
	 */
	public static Vec3 direction(float yaw, float pitch)
	{
		float rad = Mth.DEG_TO_RAD;
		float xd = -Mth.sin(yaw * rad) * Mth.cos(pitch * rad);
		float yd = -Mth.sin(pitch * rad);
		float zd = Mth.cos(yaw * rad) * Mth.cos(pitch * rad);
		return new Vec3(xd, yd, zd);
	}
	
	/** The rotation that throws in the given direction. */
	public static Rotation rotationFor(Vec3 dir)
	{
		Vec3 d = dir.normalize();
		float yaw = (float)Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float)Math.toDegrees(Math.asin(-d.y));
		return new Rotation(yaw, pitch);
	}
	
	/**
	 * @param noise
	 *            per-axis deviation, see {@link #sampleNoise(Random)}
	 * @param knownMovement
	 *            the thrower's last movement as the server knows it
	 * @param onGround
	 *            the thrower's vertical movement is only added in the air
	 */
	public static Vec3 velocity(Vec3 dir, Vec3 noise, Vec3 knownMovement,
		boolean onGround)
	{
		return dir.normalize().add(noise).scale(POWER).add(knownMovement.x,
			onGround ? 0 : knownMovement.y, knownMovement.z);
	}
	
	/** One sample of the vanilla {@code triangle(0, 0.0172275)} noise. */
	public static Vec3 sampleNoise(Random random)
	{
		return new Vec3(triangle(random), triangle(random), triangle(random));
	}
	
	private static double triangle(Random random)
	{
		return NOISE * (random.nextDouble() - random.nextDouble());
	}
}
