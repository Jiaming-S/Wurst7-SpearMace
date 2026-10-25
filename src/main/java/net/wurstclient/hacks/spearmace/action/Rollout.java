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
			
			Transition t = sim.step(state, run.merged(state.self()));
			steps.add(t);
			state = t.next();
			action = run.then();
			chance = Math.min(chance, action.chance());
		}
		return new Rollout(start, first, steps, null, chance);
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
