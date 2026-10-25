/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.action;

import net.wurstclient.hacks.spearmace.action.Step.Run;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Fills the channels that an action doesn't claim, such as aiming at the
 * target while a wind charge flies. Rollouts use the same controller as the
 * game, so its effect is part of every prediction.
 */
@FunctionalInterface
public interface Controller
{
	/** Leaves unclaimed channels alone. */
	public static final Controller NONE = (s, run) -> run;
	
	/** {@code run} with more input on channels it doesn't claim. */
	public Run fill(GameState s, Run run);
}
