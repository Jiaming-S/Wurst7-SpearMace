/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.sim.PearlSim;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Pending;
import net.wurstclient.hacks.spearmace.state.Pending.Impulse;
import net.wurstclient.hacks.spearmace.state.Pending.PearlInFlight;
import net.wurstclient.hacks.spearmace.state.Pending.TargetDamage;
import net.wurstclient.hacks.spearmace.state.Pending.Teleport;
import net.wurstclient.hacks.spearmace.state.Pending.WindChargeInFlight;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.hacks.spearmace.state.TargetState;

/**
 * Combines what the client shows now with what the model predicted for
 * now. The client is right about the player and the target, but it can't
 * see clicks the server hasn't handled yet or velocity changes on their
 * way, and it shows the player's projectiles a few ticks late. So those
 * come from the prediction, and the client's view of a projectile is only
 * used once, to correct its random spread when it first shows up.
 *
 * <p>
 * The server doesn't always take the same number of ticks, so velocity
 * changes, teleports and damage that arrive a tick early or late are moved
 * to when they really arrived. Cooldowns come from the prediction while the
 * client still shows them, because the client restarts its countdown when
 * the server's cooldown packet arrives.
 */
public enum Observer
{
	;
	
	/**
	 * @param predicted
	 *            the model's state for now, or {@code null} to take the
	 *            captured projectiles as they are
	 */
	public static GameState observe(GameState captured, GameState predicted)
	{
		if(predicted == null)
			return captured;
		
		SelfState c = captured.self();
		SelfState p = predicted.self();
		List<Pending> pending =
			retime(merge(p.pending(), c.pending()), captured, predicted);
		
		// the server resets the fall distance on smashes, but the client
		// keeps counting
		double fall = Math.min(c.fallDistance(), p.fallDistance());
		
		SelfState self = new SelfState(c.tick(), c.pos(), c.vel(), c.onGround(),
			fall, c.yaw(), c.pitch(), c.forward(), c.sprinting(), c.jump(),
			c.fallFlying(), c.attackStrengthTicker(), c.heldChanged(),
			c.missTime(), c.interactionRange(), c.knockbackResistance(),
			c.kit().withCooldownsFrom(p.kit()), pending);
		TargetState target = captured.target() == null ? null
			: captured.target().withTimersFrom(predicted.target());
		return new GameState(self, target);
	}
	
	private static List<Pending> merge(List<Pending> predicted,
		List<Pending> seen)
	{
		HashSet<Integer> bound = new HashSet<>();
		for(Pending p : predicted)
			if(entityId(p) >= 0)
				bound.add(entityId(p));
			
		ArrayList<Pending> merged = new ArrayList<>();
		for(Pending p : predicted)
		{
			if(entityId(p) < 0)
			{
				Pending sighting = firstSighting(p, seen, bound);
				if(sighting != null)
				{
					bound.add(entityId(sighting));
					merged.add(sighting);
					continue;
				}
			}
			merged.add(p);
		}
		return merged;
	}
	
	/**
	 * The client's view of the unseen projectile {@code p}, moved forward to
	 * the predicted age, or {@code null} if it doesn't show it yet.
	 */
	private static Pending firstSighting(Pending p, List<Pending> seen,
		HashSet<Integer> bound)
	{
		for(Pending s : seen)
		{
			if(bound.contains(entityId(s)))
				continue;
			
			if(p instanceof PearlInFlight pearl && s instanceof PearlInFlight c)
			{
				PearlSim sim = new PearlSim(c.pos(), c.vel(), c.age());
				while(sim.age < pearl.age())
					sim.stepFree();
				sim.age = pearl.age();
				return PearlInFlight.of(c.entityId(), sim);
			}
			
			if(p instanceof WindChargeInFlight charge
				&& s instanceof WindChargeInFlight c)
			{
				int ahead = Math.max(0, charge.age() - c.age());
				Vec3 pos = c.pos().add(c.vel().scale(ahead));
				return new WindChargeInFlight(c.entityId(), pos, c.vel(),
					charge.age(), charge.leftOwner());
			}
		}
		return null;
	}
	
	/**
	 * Re-arms applied effects that the client doesn't show yet and drops
	 * pending ones that it already shows.
	 */
	private static List<Pending> retime(List<Pending> pending,
		GameState captured, GameState predicted)
	{
		int next = captured.self().tick() + 1;
		ArrayList<Pending> retimed = new ArrayList<>();
		for(Pending p : pending)
			if(isLate(p, captured.self(), predicted.self()))
				retimed.add(switch(p)
				{
					case Impulse i -> i.rearmedAt(next);
					case Teleport t -> t.rearmedAt(next);
					default -> p;
				});
			else if(!isEarly(p, captured, predicted, next))
				retimed.add(p);
		return retimed;
	}
	
	/** Applied in the prediction, but the client doesn't show it yet. */
	private static boolean isLate(Pending p, SelfState c, SelfState predicted)
	{
		if(p instanceof Teleport t)
			return t.applied() && !t.late() && c.pos().distanceTo(t.to()) > 1;
		
		if(!(p instanceof Impulse i) || !i.applied() || i.late())
			return false;
		
		if(!Double.isNaN(i.setY()))
			return Math.abs(c.vel().y - i.setY()) > 0.5;
		
		return explains(predicted.vel().subtract(c.vel()), i.add());
	}
	
	/** Due next tick, but the client already shows it. */
	private static boolean isEarly(Pending p, GameState captured,
		GameState predicted, int next)
	{
		SelfState c = captured.self();
		switch(p)
		{
			case Teleport t:
			return !t.applied() && c.pos().distanceTo(t.to()) < 0.5;
			
			case TargetDamage d:
			return d.applyAt() == next && captured.target() != null
				&& predicted.target() != null && captured.target()
					.health() < predicted.target().health() - d.damage() / 2;
			
			case Impulse i:
			if(i.applied() || i.applyAt() != next)
				return false;
			if(!Double.isNaN(i.setY()))
				return Math.abs(c.vel().y - i.setY()) < 0.1
					&& Math.abs(predicted.self().vel().y - i.setY()) > 0.5;
			return explains(c.vel().subtract(predicted.self().vel()), i.add());
			
			default:
			return false;
		}
	}
	
	/** Whether most of {@code residual} is {@code change}. */
	static boolean explains(Vec3 residual, Vec3 change)
	{
		double size = change.length();
		return size > 0.1 && residual.subtract(change).length() < size / 2;
	}
	
	private static int entityId(Pending p)
	{
		return switch(p)
		{
			case PearlInFlight pearl -> pearl.entityId();
			case WindChargeInFlight charge -> charge.entityId();
			default -> -1;
		};
	}
}
