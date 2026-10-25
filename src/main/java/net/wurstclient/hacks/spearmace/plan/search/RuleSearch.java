/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan.search;

import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.plan.Budget;
import net.wurstclient.hacks.spearmace.plan.Outcome;
import net.wurstclient.hacks.spearmace.plan.Plan;
import net.wurstclient.hacks.spearmace.plan.Search;
import net.wurstclient.hacks.spearmace.plan.SearchProblem;

/**
 * No lookahead: the first action, in the problem's order, that the model
 * expects to work. With {@code Options} that is the hand-written rules.
 */
public final class RuleSearch implements Search
{
	@Override
	public Plan search(SearchProblem problem, Budget budget)
	{
		for(Action action : problem.actions(problem.root()))
		{
			Outcome o = problem.play(problem.root(), action);
			if(!o.failed())
				return Plan.of(o, problem.score(o) + problem.value(o.end()),
					"first that works");
		}
		return Plan.NOTHING;
	}
}
