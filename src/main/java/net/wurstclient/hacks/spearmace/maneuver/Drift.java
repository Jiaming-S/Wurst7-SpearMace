/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.maneuver;

import net.minecraft.world.level.Level;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Step;
import net.wurstclient.hacks.spearmace.combat.Engagement;
import net.wurstclient.hacks.spearmace.model.TickInput;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Sprints forward through the air toward the target, steering at it every
 * update. Air control is weak, but over a long fall it closes gaps that
 * are too small for a pearl catch and too big for a strike. Done on
 * landing or after {@code ticks} updates.
 */
public record Drift(int ticks) implements Action
{
	@Override
	public String name()
	{
		return "Drift";
	}
	
	@Override
	public Step next(Level level, GameState s)
	{
		if(s.self().onGround())
			return Step.fail("on the ground");
		return new Drifting(s.self().tick() + ticks).next(level, s);
	}
	
	private record Drifting(int until) implements Action
	{
		@Override
		public String name()
		{
			return "Drift";
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			if(!s.targetAlive())
				return Step.fail("no target");
			if(s.self().onGround() || s.self().tick() >= until)
				return Step.done(null);
			
			TickInput steer = TickInput.NONE.withRotation(Engagement
				.aim(s.self().eyePos(), s.target().box().getCenter()));
			return Step.run(steer, this).moving(true);
		}
	}
}
