/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

import java.util.List;
import java.util.stream.Collectors;

import net.wurstclient.hacks.spearmace.action.Action;

/**
 * What to do until the next decision point (and maybe beyond).
 *
 * @param expected
 *            the outcome of each action, for logs and the preview
 * @param value
 *            the search's score for the whole plan
 * @param why
 *            how the search picked it, for logs
 */
public record Plan(List<Action> actions, List<Outcome> expected, double value,
	String why)
{
	public static final Plan NOTHING =
		new Plan(List.of(), List.of(), 0, "nothing works");
	
	public Plan
	{
		actions = List.copyOf(actions);
		expected = List.copyOf(expected);
	}
	
	/** A plan of just the action of {@code o}. */
	public static Plan of(Outcome o, double value, String why)
	{
		return new Plan(List.of(o.action()), List.of(o), value, why);
	}
	
	@Override
	public String toString()
	{
		return actions.stream().map(Action::name)
			.collect(Collectors.joining(" -> ", "[", "]"))
			+ String.format(" value=%.1f (%s)", value, why);
	}
}
