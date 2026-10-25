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
 * Something the bot can do, such as a wind charge jump or a mace strike,
 * written as a state machine whose states are values: each update,
 * {@link #next} says what to press and which action handles the next
 * update.
 *
 * <p>
 * {@link #next} only reads its arguments, never the live game, so the same
 * code runs the action in the game and predicts it in a {@link Rollout}.
 * Checks that the action worked compare the states it is given, so a
 * prediction that the real game doesn't follow shows up as a failure.
 */
public interface Action
{
	public String name();
	
	/**
	 * What to do at the update with state {@code s}.
	 *
	 * @param level
	 *            for collisions only
	 */
	public Step next(Level level, GameState s);
	
	/**
	 * The chance that the rest of this action works out, below 1 if it
	 * depends on random projectile spread.
	 */
	public default double chance()
	{
		return 1;
	}
}
