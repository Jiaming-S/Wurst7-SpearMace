/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan.eval;

import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.model.Event;
import net.wurstclient.hacks.spearmace.plan.Evaluator;
import net.wurstclient.hacks.spearmace.plan.Outcome;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Pending.Teleport;
import net.wurstclient.hacks.spearmace.state.SelfState;

/**
 * Expected damage dealt, minus what it cost: fall damage (also the
 * expected fall damage of a missed pearl catch), items and time.
 * A state is worth the height the next smash can use (and loses as much
 * for being below the target), a wind charge to launch again with, and
 * being close enough to strike. A pending pearl teleport counts as already
 * being there.
 *
 * <p>
 * The player's own health counts too, as {@link SelfState#expectedHealth()}
 * : each point missing from full health plus a golden apple's
 * absorption costs, and more for each point below {@link #LOW_HEALTH}. Low
 * health close to the
 * target costs extra, as nothing models the target hitting back.
 */
public final class DamageRace implements Evaluator
{
	/** Horizontal distance that a strike from above still covers. */
	private static final double STRIKE_RANGE = 3;
	
	/** Below this much effective health, each point is worth more. */
	public static final double LOW_HEALTH = 10;
	
	/** Effective health above full health that still counts. */
	private static final double EXTRA_HEALTH = 4;
	
	/** Closer than this to the target, low health is in danger. */
	public static final double THREAT_RANGE = 6;
	
	private final Weights w;
	
	public DamageRace(Weights weights)
	{
		w = weights;
	}
	
	public DamageRace()
	{
		this(Weights.DEFAULT);
	}
	
	@Override
	public double score(Outcome o)
	{
		double taken = o.damageTaken() + (1 - o.chance()) * o.missDamage();
		return o.chance() * o.damageDealt() - w.fallDamage() * taken
			- w.windCharge() * o.count(Event.THREW_WIND_CHARGE)
			- w.pearl() * o.count(Event.THREW_PEARL)
			- w.apple() * o.count(Event.ATE) - w.tick() * o.ticks();
	}
	
	@Override
	public double value(GameState s)
	{
		if(!s.targetAlive())
			return 0;
		
		SelfState self = s.self();
		Vec3 pos = self.pos();
		for(Teleport t : self.pending(Teleport.class))
			if(!t.applied())
				pos = t.to();
			
		double height = pos.y - s.target().eyePos().y;
		double value =
			w.height() * Math.clamp(height, -w.maxHeight(), w.maxHeight());
		if(self.kit().windCharge() >= 0)
			value += w.ready();
		double gap = pos.subtract(s.target().pos()).horizontalDistance();
		value -= w.distance() * Math.max(0, gap - STRIKE_RANGE);
		return value + health(s, pos);
	}
	
	private double health(GameState s, Vec3 pos)
	{
		SelfState self = s.self();
		double health = self.expectedHealth();
		double cap = self.vitals().maxHealth() + EXTRA_HEALTH;
		double low = Math.max(0, LOW_HEALTH - health);
		double missing = Math.max(0, cap - health);
		double value = -w.health() * (missing + low * low / LOW_HEALTH);
		
		double near = THREAT_RANGE - pos.distanceTo(s.target().eyePos());
		if(near > 0)
			value -= w.threat() * low / LOW_HEALTH * near / THREAT_RANGE;
		return value;
	}
}
