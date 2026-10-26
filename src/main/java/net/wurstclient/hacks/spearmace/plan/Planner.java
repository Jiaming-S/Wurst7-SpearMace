/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.level.Level;
import net.wurstclient.hacks.spearmace.action.Controller;
import net.wurstclient.hacks.spearmace.model.TargetModel;
import net.wurstclient.hacks.spearmace.model.TickSim;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.viz.Preview;

/**
 * Picks what to do from now until the next decision point. Which
 * {@link Search} and which {@link Evaluator} is up to the constructor, so
 * trying another one is a one-line change.
 */
public final class Planner
{
	private static final Logger LOGGER = LoggerFactory.getLogger("SpearMace");
	
	private final TargetModel targetModel;
	private final Options options;
	private final Controller controller;
	private final Search search;
	private final Evaluator evaluator;
	private final long budgetMillis;
	
	private long totalNanos;
	private int replans;
	
	public Planner(TargetModel targetModel, Options options,
		Controller controller, Search search, Evaluator evaluator,
		long budgetMillis)
	{
		this.targetModel = targetModel;
		this.options = options;
		this.controller = controller;
		this.search = search;
		this.evaluator = evaluator;
		this.budgetMillis = budgetMillis;
	}
	
	public Plan replan(Level level, GameState now)
	{
		TickSim model = new TickSim(level, targetModel);
		SearchProblem problem =
			new ModelProblem(now, model, options, controller, evaluator);
		SearchTrace trace =
			Preview.isTracing() ? new SearchTrace(problem) : null;
		if(trace != null)
			problem = trace;
		long start = System.nanoTime();
		Plan plan = search.search(problem, Budget.millis(budgetMillis));
		long nanos = System.nanoTime() - start;
		if(trace != null)
			Preview.showTree(now, trace, plan, nanos);
		totalNanos += nanos;
		replans++;
		LOGGER.info("[{}] plan {} in {} ms", now.self().tick(), plan,
			nanos / 1_000_000);
		return plan;
	}
	
	/** How long a replan took on average so far. */
	public double meanMillis()
	{
		return replans == 0 ? 0 : totalNanos / 1e6 / replans;
	}
	
	/** Fills what the plan's actions leave unclaimed. */
	public Controller controller()
	{
		return controller;
	}
}
