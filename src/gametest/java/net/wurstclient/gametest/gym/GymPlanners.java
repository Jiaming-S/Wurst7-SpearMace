/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import net.wurstclient.hacks.SpearMaceHack;
import net.wurstclient.hacks.SpearMaceHack.SearchKind;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.plan.Budget;
import net.wurstclient.hacks.spearmace.plan.Outcome;
import net.wurstclient.hacks.spearmace.plan.Plan;
import net.wurstclient.hacks.spearmace.plan.Planner;
import net.wurstclient.hacks.spearmace.plan.Search;
import net.wurstclient.hacks.spearmace.plan.SearchProblem;
import net.wurstclient.hacks.spearmace.plan.eval.Weights;

/** Turns {@link GymConfig}s into SpearMace planners, on the bot client. */
public enum GymPlanners
{
	;
	
	/** What {@link Plan#why()} says for an exploration move. */
	public static final String EXPLORE = "explore";
	
	public static Planner of(GymConfig c)
	{
		SearchKind kind = switch(c.search())
		{
			case "Rules" -> SearchKind.RULES;
			case "MCTS" -> SearchKind.MCTS;
			default -> SearchKind.BEAM;
		};
		Weights d = Weights.DEFAULT;
		Weights weights = new Weights(c.fallDamage(), c.windCharge(), c.pearl(),
			d.tick(), c.height(), d.maxHeight(), d.ready(), c.distance());
		return SpearMaceHack.planner(new SpearMaceHack.Config(kind, c.width(),
			c.depth(), c.iterations(), c.budget(), weights));
	}
	
	/**
	 * {@code planner}, except that with probability {@code epsilon} it plays
	 * a random action that the model thinks works instead of the plan. It
	 * still searches first, so the search tree is there either way.
	 */
	public static Planner exploring(Planner planner, double epsilon,
		Random random)
	{
		if(epsilon <= 0)
			return planner;
		return planner
			.withSearch(new Exploring(planner.search(), epsilon, random));
	}
	
	/**
	 * What {@code planner}'s search picked for {@code acted}, which differs
	 * from it only after an exploration move.
	 */
	public static Plan searched(Planner planner, Plan acted)
	{
		return planner.search() instanceof Exploring e && e.lastSearched != null
			? e.lastSearched : acted;
	}
	
	private static final class Exploring implements Search
	{
		private final Search inner;
		private final double epsilon;
		private final Random random;
		private Plan lastSearched;
		
		private Exploring(Search inner, double epsilon, Random random)
		{
			this.inner = inner;
			this.epsilon = epsilon;
			this.random = random;
		}
		
		@Override
		public Plan search(SearchProblem problem, Budget budget)
		{
			Plan plan = inner.search(problem, budget);
			lastSearched = plan;
			if(random.nextDouble() >= epsilon)
				return plan;
			
			List<Action> actions =
				new ArrayList<>(problem.actions(problem.root()));
			Collections.shuffle(actions, random);
			for(Action a : actions)
			{
				Outcome o = problem.play(problem.root(), a);
				if(!o.failed())
					return Plan.of(o, problem.score(o) + problem.value(o.end()),
						EXPLORE);
			}
			return plan;
		}
	}
}
