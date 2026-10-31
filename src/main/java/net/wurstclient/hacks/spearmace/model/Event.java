/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.model;

/** Something notable that happened during one {@link TickSim} step. */
public enum Event
{
	THREW_WIND_CHARGE,
	THREW_PEARL,
	
	STARTED_EATING,
	
	/** The player finished eating a golden apple. */
	ATE,
	
	/** Eating stopped before it was done. */
	STOPPED_EATING,
	
	/** An attack click that hit nothing, starting the miss cooldown. */
	MISSED,
	
	/** A spear jab, which lunges if the spear has Lunge. */
	JABBED,
	
	/** An attack that damaged the target. */
	STRUCK,
	
	/** An attack that the target's shield blocked. */
	BLOCKED,
	
	/** An attack that knocked the target's shield down. */
	SHIELD_DISABLED,
	
	/** A velocity change from the server reached the player. */
	IMPULSE,
	
	EXPLODED,
	
	/** A pearl teleport reached the player. */
	TELEPORTED,
	
	/** The pearl hit a block instead of a wind charge. */
	PEARL_LANDED,
	
	/** The player started falling. */
	APEX,
	
	LANDED;
}
