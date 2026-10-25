/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.action;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.action.Step.Failed;
import net.wurstclient.hacks.spearmace.action.Step.Run;
import net.wurstclient.hacks.spearmace.model.TickSim;
import net.wurstclient.hacks.spearmace.model.Transition;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * What an action is expected to do: the forward model stepped with the
 * action's own inputs until it is done.
 *
 * @param first
 *            the action's step at {@code start}, which is also what it does
 *            in the game if it starts there
 * @param end
 *            the step that ended it, or {@code null} if it was still running
 *            after the maximum number of ticks
 * @param chance
 *            the lowest {@link Action#chance()} along the way
 */
public record Rollout(GameState start, Step first, List<Transition> steps,
	Step end, double chance)
{
	public static Rollout of(TickSim sim, GameState start, Action action,
		int maxTicks)
	{
		return of(sim, start, action, maxTicks, Controller.NONE);
	}
	
	/** With {@code controller} filling the unclaimed channels. */
	public static Rollout of(TickSim sim, GameState start, Action action,
		int maxTicks, Controller controller)
	{
		return of(sim, start, action, maxTicks, controller, (a, b) -> false);
	}
	
	/**
	 * Also stops at the first decision point, a pair of consecutive states
	 * that {@code decisionPoint} accepts, unless the action is committed.
	 * The action then gets one more look at the state, so that it can say
	 * it is done or has failed; otherwise {@link #end()} is {@code null}.
	 */
	public static Rollout of(TickSim sim, GameState start, Action action,
		int maxTicks, Controller controller,
		BiPredicate<GameState, GameState> decisionPoint)
	{
		ArrayList<Transition> steps = new ArrayList<>();
		GameState state = start;
		Step first = null;
		double chance = action.chance();
		for(int i = 0; i < maxTicks; i++)
		{
			Step step = action.next(sim.level(), state);
			if(first == null)
				first = step;
			if(!(step instanceof Run run))
				return new Rollout(start, first, steps, step, chance);
			
			run = controller.fill(state, run);
			Transition t = sim.step(state, run.merged(state.self()));
			steps.add(t);
			GameState before = state;
			state = t.next();
			action = run.then();
			chance = Math.min(chance, action.chance());
			
			if(!run.committed() && decisionPoint.test(before, state))
			{
				Step look = action.next(sim.level(), state);
				return new Rollout(start, first, steps,
					look instanceof Run ? null : look, chance);
			}
		}
		return new Rollout(start, first, steps, null, chance);
	}
	
	public boolean failed()
	{
		return end instanceof Failed;
	}
	
	public GameState last()
	{
		return steps.isEmpty() ? start : steps.getLast().next();
	}
	
	public Prediction prediction()
	{
		if(end instanceof Failed failed)
			return Prediction.infeasible(failed.reason());
		
		ArrayList<Vec3> path = new ArrayList<>();
		path.add(start.self().pos());
		for(Transition t : steps)
			path.add(t.next().self().pos());
		return Prediction.of(path, last().self().vel(), chance);
	}
}
