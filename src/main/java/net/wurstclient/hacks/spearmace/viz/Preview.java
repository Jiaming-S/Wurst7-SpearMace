/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.viz;

import java.util.List;

import net.wurstclient.hacks.spearmace.model.Transition;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Where actions and planners publish the future they expect, so that
 * SpearMacePreview can draw it. Publishing is cheap and does nothing
 * visible unless that hack is enabled.
 */
public enum Preview
{
	;
	
	/** Plans older than this many updates aren't drawn any more. */
	public static final int MAX_AGE = 40;
	
	/**
	 * @param rollout
	 *            the steps from {@code start}
	 */
	public record Plan(String label, GameState start, List<Transition> rollout)
	{
		public int tick()
		{
			return start.self().tick();
		}
	}
	
	private static volatile Plan plan;
	
	public static void show(String label, GameState start,
		List<Transition> rollout)
	{
		plan = new Plan(label, start, List.copyOf(rollout));
	}
	
	public static void clear()
	{
		plan = null;
	}
	
	/** The latest plan, or {@code null} if it is older than {@code now}. */
	public static Plan latest(int now)
	{
		Plan p = plan;
		return p == null || now - p.tick() > MAX_AGE ? null : p;
	}
}
