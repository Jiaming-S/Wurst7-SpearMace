/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.action;

import net.minecraft.world.level.Level;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Does nothing for a while, leaving every channel to the controller. Done
 * after {@code ticks} updates, so the planner looks again.
 */
public record Hold(String why, int ticks) implements Action
{
	@Override
	public String name()
	{
		return "Hold(" + why + ")";
	}
	
	@Override
	public Step next(Level level, GameState s)
	{
		return new Holding(name(), s.self().tick() + ticks).next(level, s);
	}
	
	private record Holding(String name, int until) implements Action
	{
		@Override
		public Step next(Level level, GameState s)
		{
			if(s.self().tick() >= until)
				return Step.done(null);
			return Step.idle(this);
		}
	}
}
