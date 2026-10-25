/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.combat;

import net.minecraft.world.entity.LivingEntity;
import net.wurstclient.hacks.spearmace.combat.StrikeSelector.Decision;
import net.wurstclient.hacks.spearmace.maneuver.Maneuver;
import net.wurstclient.hacks.spearmace.maneuver.Prediction;

/**
 * Lets {@link StrikeSelector} pick the strike when this maneuver starts,
 * rather than when it is queued, then runs it.
 */
public final class Engage implements Maneuver
{
	private final LivingEntity target;
	private Decision decision;
	
	public Engage(LivingEntity target)
	{
		this.target = target;
	}
	
	@Override
	public String name()
	{
		return decision == null ? "Engage" : "Engage(" + decision.kind() + ")";
	}
	
	@Override
	public Prediction predict()
	{
		decision = StrikeSelector.decide(target);
		if(decision.maneuver() == null)
			return Prediction.infeasible(decision.reason());
		
		return decision.maneuver().predict();
	}
	
	@Override
	public Status tick(long tick)
	{
		if(decision == null)
			predict();
		if(decision.maneuver() == null)
			return Status.FAILED;
		
		return decision.maneuver().tick(tick);
	}
	
	@Override
	public String failureReason()
	{
		if(decision == null)
			return null;
		if(decision.maneuver() == null)
			return decision.reason();
		return decision.maneuver().failureReason();
	}
	
	/** What was picked, or {@code null} before the maneuver started. */
	public Decision decision()
	{
		return decision;
	}
}
