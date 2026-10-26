/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

import java.util.List;

import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Controller;
import net.wurstclient.hacks.spearmace.action.Rollout;
import net.wurstclient.hacks.spearmace.model.TickSim;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * The {@link SearchProblem} of the forward model: actions are played
 * through {@link TickSim} with the controller filling in, until they end
 * or {@link Triggers} finds a decision point, the same as in the game.
 */
public final class ModelProblem implements SearchProblem
{
	/** Actions still running after this many ticks are cut off. */
	private static final int MAX_TICKS = 100;
	
	private final GameState root;
	private final TickSim model;
	private final Options options;
	private final Controller controller;
	private final Evaluator evaluator;
	
	public ModelProblem(GameState root, TickSim model, Options options,
		Controller controller, Evaluator evaluator)
	{
		this.root = root;
		this.model = model;
		this.options = options;
		this.controller = controller;
		this.evaluator = evaluator;
	}
	
	@Override
	public GameState root()
	{
		return root;
	}
	
	@Override
	public List<Action> actions(GameState s)
	{
		return options.of(model.level(), s);
	}
	
	@Override
	public Outcome play(GameState s, Action a)
	{
		Rollout rollout = Rollout.of(model, s, a, MAX_TICKS, controller, (
			before,
			after) -> !Triggers.detect(before, after, null, null).isEmpty());
		return Outcome.of(a, rollout);
	}
	
	@Override
	public double score(Outcome o)
	{
		return evaluator.score(o);
	}
	
	@Override
	public double value(GameState s)
	{
		return evaluator.value(s);
	}
	
	@Override
	public boolean isTerminal(GameState s)
	{
		return !s.targetAlive();
	}
}
