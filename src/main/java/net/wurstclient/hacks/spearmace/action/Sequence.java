/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.action;

import net.minecraft.world.level.Level;
import net.wurstclient.hacks.spearmace.action.Step.Done;
import net.wurstclient.hacks.spearmace.action.Step.Run;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Runs {@code first}, then {@code then} from the update where
 * {@code first} is done. Done with {@code then}'s result, and fails if
 * either fails.
 */
public record Sequence(String name, Action first, Action then) implements Action
{
	@Override
	public Step next(Level level, GameState s)
	{
		Step step = first.next(level, s);
		return switch(step)
		{
			case Run run -> run.withThen(new Sequence(name, run.then(), then));
			case Done done -> then.next(level, s);
			default -> step;
		};
	}
	
	@Override
	public double chance()
	{
		return first.chance() * then.chance();
	}
}
