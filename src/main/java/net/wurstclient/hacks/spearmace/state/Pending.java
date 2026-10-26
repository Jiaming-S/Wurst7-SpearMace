/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.state;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.sim.PearlSim;
import net.wurstclient.hacks.spearmace.sim.WindChargeSim;

/**
 * Something the player started that hasn't had its effect yet. A state
 * halfway through a maneuver is defined by these as much as by the
 * player's position and velocity.
 *
 * <p>
 * Ticks are numbered like {@link SelfState#tick()}. Server tick {@code n}
 * runs after client tick {@code n} and handles the clicks that client tick
 * sent. Its velocity changes show up in the state at tick {@code n + 2}.
 */
public sealed interface Pending
{
	/**
	 * One of the player's own wind charges, on the server.
	 *
	 * @param entityId
	 *            the client entity, or -1 if it hasn't been seen yet
	 * @param leftOwner
	 *            {@code Projectile.leftOwner}: once the charge is clear of
	 *            the player, it can hit the player
	 */
	public record WindChargeInFlight(int entityId, Vec3 pos, Vec3 vel, int age,
		boolean leftOwner) implements Pending
	{
		public WindChargeSim toSim()
		{
			WindChargeSim sim = new WindChargeSim(pos, vel);
			sim.age = age;
			return sim;
		}
		
		public static WindChargeInFlight of(int entityId, WindChargeSim sim,
			boolean leftOwner)
		{
			return new WindChargeInFlight(entityId, sim.pos, sim.vel, sim.age,
				leftOwner);
		}
		
		/** {@code Projectile.checkLeftOwner()} */
		public static boolean isClearOf(WindChargeSim sim, AABB owner)
		{
			return !sim.box().expandTowards(sim.vel).inflate(1)
				.intersects(owner);
		}
	}
	
	/**
	 * One of the player's own ender pearls, on the server.
	 *
	 * @param entityId
	 *            the client entity, or -1 if it hasn't been seen yet
	 */
	public record PearlInFlight(int entityId, Vec3 pos, Vec3 vel, int age)
		implements Pending
	{
		public PearlSim toSim()
		{
			return new PearlSim(pos, vel, age);
		}
		
		public static PearlInFlight of(int entityId, PearlSim sim)
		{
			return new PearlInFlight(entityId, sim.pos, sim.vel, sim.age);
		}
	}
	
	public enum Projectile
	{
		WIND_CHARGE,
		PEARL;
	}
	
	/**
	 * A use click that the server handles in the next server tick, which
	 * spawns the projectile.
	 *
	 * @param feet
	 *            where the server thinks the player is
	 * @param knownMovement
	 *            the player's last movement as the server knows it
	 */
	public record ServerUse(Projectile projectile, Vec3 feet,
		Vec3 knownMovement, boolean onGround, float yaw, float pitch)
		implements Pending
	{}
	
	/**
	 * An attack on the target that the server handles in the next server
	 * tick. The client already worked out what it does.
	 *
	 * @param damage
	 *            before armor
	 * @param crit
	 *            whether {@code damage} includes the critical hit multiplier
	 * @param smash
	 *            whether it is a mace smash, which resets the fall distance
	 *            and launches the player
	 * @param launchVelY
	 *            the player's y velocity after a smash
	 * @param disableShieldTicks
	 *            how long the weapon disables a raised shield, 0 if not
	 * @param feet
	 *            where the server thinks the player is
	 */
	public record ServerAttack(double damage, boolean crit, boolean smash,
		double launchVelY, int disableShieldTicks, Vec3 feet) implements Pending
	{}
	
	/** A spear jab that the server handles in the next server tick. */
	public record ServerLunge(Vec3 impulse) implements Pending
	{}
	
	/**
	 * A velocity change from the server that shows up at tick
	 * {@code applyAt}. Once applied, it stays for one more tick so that the
	 * {@code Observer} can check that it really arrived.
	 *
	 * @param add
	 *            added to the velocity
	 * @param setY
	 *            replaces the y velocity first, or NaN
	 * @param late
	 *            whether it was already moved to a later tick once
	 */
	public record Impulse(int applyAt, Vec3 add, double setY, boolean applied,
		boolean late) implements Pending
	{
		public Impulse(int applyAt, Vec3 add, double setY)
		{
			this(applyAt, add, setY, false, false);
		}
		
		public Vec3 apply(Vec3 vel)
		{
			Vec3 v = Double.isNaN(setY) ? vel : new Vec3(vel.x, setY, vel.z);
			return v.add(add);
		}
		
		public Impulse asApplied()
		{
			return new Impulse(applyAt, add, setY, true, late);
		}
		
		/** The same impulse, arriving at {@code tick} instead. */
		public Impulse rearmedAt(int tick)
		{
			return new Impulse(tick, add, setY, false, true);
		}
	}
	
	/**
	 * A pearl teleport from the server that shows up at {@code applyAt}.
	 * Like an {@link Impulse}, it stays for one more tick once applied.
	 */
	public record Teleport(int applyAt, Vec3 to, boolean applied, boolean late)
		implements Pending
	{
		public Teleport(int applyAt, Vec3 to)
		{
			this(applyAt, to, false, false);
		}
		
		public Teleport asApplied()
		{
			return new Teleport(applyAt, to, true, late);
		}
		
		public Teleport rearmedAt(int tick)
		{
			return new Teleport(tick, to, false, true);
		}
	}
	
	/** Damage the client sees the target take at {@code applyAt}. */
	public record TargetDamage(int applyAt, double damage) implements Pending
	{}
}
