/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.model;

import java.util.EnumSet;

import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * The result of one {@link TickSim} step.
 *
 * @param damageDealt
 *            to the target, before armor
 * @param fallDamage
 *            taken by the player on landing, before armor and effects
 */
public record Transition(GameState next, EnumSet<Event> events,
	double damageDealt, double fallDamage)
{
	public boolean has(Event event)
	{
		return events.contains(event);
	}
}
