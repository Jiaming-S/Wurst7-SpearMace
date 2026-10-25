/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Scores plans as the reward of each segment plus an estimate of where
 * the last one leaves us, so that searches of any depth compare alike.
 */
public interface Evaluator
{
	/** The reward of one segment: damage dealt, damage taken and so on. */
	public double score(Outcome o);
	
	/** What a state is worth beyond what got us there. */
	public double value(GameState s);
}
