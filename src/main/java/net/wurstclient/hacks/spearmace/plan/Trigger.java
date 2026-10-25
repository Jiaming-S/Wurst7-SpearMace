/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

/**
 * A decision point: something changed enough that the plan should be
 * looked at again. See {@link Triggers}.
 */
public enum Trigger
{
	/** The player started falling. */
	APEX,
	
	LANDED,
	
	/** A pearl moved the player. */
	TELEPORTED,
	
	/** Something pushed the player upward, such as a wind burst. */
	LAUNCHED,
	
	SHIELD_UP,
	SHIELD_DOWN,
	
	/** A target showed up, went away or was replaced. */
	TARGET_CHANGED,
	
	/** The player isn't where the model expected. */
	DIVERGED;
}
