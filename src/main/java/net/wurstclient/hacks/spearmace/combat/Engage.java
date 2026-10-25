/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.combat;

import net.minecraft.world.level.Level;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Step;
import net.wurstclient.hacks.spearmace.action.Step.Done;
import net.wurstclient.hacks.spearmace.action.Step.Failed;
import net.wurstclient.hacks.spearmace.action.Step.Run;
import net.wurstclient.hacks.spearmace.combat.StrikeSelector.Decision;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Lets {@link StrikeSelector} pick the strike when this action starts,
 * then runs it.
 */
public record Engage() implements Action
{
	/**
	 * What the engagement did.
	 *
	 * @param result
	 *            what the picked strike measured
	 */
	public record Engaged(Decision decision, Object result)
	{}
	
	@Override
	public String name()
	{
		return "Engage";
	}
	
	@Override
	public Step next(Level level, GameState s)
	{
		if(!s.targetAlive())
			return Step.fail("no target");
		
		Decision decision = StrikeSelector.decide(Engagement.of(level, s));
		if(decision.action() == null)
			return Step.fail(decision.toString());
		return new Running(decision, decision.action()).next(level, s);
	}
	
	/** Runs the picked strike, keeping the decision for its result. */
	private record Running(Decision decision, Action strike) implements Action
	{
		@Override
		public String name()
		{
			return "Engage(" + decision.kind() + ")";
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			return switch(strike.next(level, s))
			{
				case Run run -> run.withThen(new Running(decision, run.then()));
				case Done done -> Step
					.done(new Engaged(decision, done.result()));
				case Failed failed -> Step
					.fail(decision.kind() + ": " + failed.reason());
			};
		}
		
		@Override
		public double chance()
		{
			return strike.chance();
		}
	}
}
