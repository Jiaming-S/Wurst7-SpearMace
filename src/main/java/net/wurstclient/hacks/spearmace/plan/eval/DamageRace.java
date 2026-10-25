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
 * Expected damage dealt, minus what it cost: fall damage, items and time.
 * A state is worth the height the next smash can use, a wind charge to
 * launch again with, and being close enough to strike. A pending pearl
 * teleport counts as already being there.
 */
public final class DamageRace implements Evaluator
{
	/** Horizontal distance that a strike from above still covers. */
	private static final double STRIKE_RANGE = 3;
	
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
		return o.chance() * o.damageDealt() - w.fallDamage() * o.damageTaken()
			- w.windCharge() * o.count(Event.THREW_WIND_CHARGE)
			- w.pearl() * o.count(Event.THREW_PEARL) - w.tick() * o.ticks();
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
		double value = w.height() * Math.clamp(height, 0, w.maxHeight());
		if(self.kit().windCharge() >= 0)
			value += w.ready();
		double gap = pos.subtract(s.target().pos()).horizontalDistance();
		return value - w.distance() * Math.max(0, gap - STRIKE_RANGE);
	}
}
