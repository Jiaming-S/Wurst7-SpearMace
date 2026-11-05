/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.plan.Budget;
import net.wurstclient.hacks.spearmace.plan.Outcome;
import net.wurstclient.hacks.spearmace.plan.Plan;
import net.wurstclient.hacks.spearmace.plan.Search;
import net.wurstclient.hacks.spearmace.plan.SearchProblem;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Plays what a {@link PolicyModel} likes best: every candidate is played
 * once in the model, as the gym logs them, then scored, and the
 * best-scoring one that the model expects to work is the plan. No
 * lookahead, so the budget doesn't matter.
 *
 * <p>
 * The features come from the same JSON the gym writes for a decision, see
 * {@link PolicyFeatures}.
 */
public final class PolicySearch implements Search
{
	private final PolicyModel model;
	
	public PolicySearch(PolicyModel model)
	{
		this.model = model;
	}
	
	@Override
	public Plan search(SearchProblem problem, Budget budget)
	{
		GameState root = problem.root();
		if(root.target() == null)
			return Plan.NOTHING;
		
		List<Action> actions = problem.actions(root);
		ArrayList<Outcome> outcomes = new ArrayList<>();
		JsonArray candidates = new JsonArray();
		for(Action a : actions)
		{
			Outcome o = problem.play(root, a);
			outcomes.add(o);
			JsonObject json = new JsonObject();
			json.addProperty("action", a.name());
			json.addProperty("type", a.getClass().getSimpleName());
			json.add("params", GymJson.of(a));
			json.add("outcome", GymAgent.outcome(o));
			candidates.add(json);
		}
		
		JsonObject decision = new JsonObject();
		decision.add("self", GymJson.of(root.self()));
		decision.add("target", GymJson.of(root.target()));
		decision.add("candidates", candidates);
		double[][] features = PolicyFeatures.of(decision, model.featureNames());
		
		int best = -1;
		double bestScore = Double.NEGATIVE_INFINITY;
		for(int i = 0; i < features.length; i++)
		{
			if(outcomes.get(i).failed())
				continue;
			double score = model.score(features[i]);
			if(score > bestScore)
			{
				best = i;
				bestScore = score;
			}
		}
		if(best < 0)
			return Plan.NOTHING;
		
		return Plan.of(outcomes.get(best), bestScore, "policy " + model.name());
	}
}
