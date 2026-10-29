/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.world.level.Level;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Rollout;
import net.wurstclient.hacks.spearmace.action.Step.Failed;
import net.wurstclient.hacks.spearmace.maneuver.Cushion;
import net.wurstclient.hacks.spearmace.model.Event;
import net.wurstclient.hacks.spearmace.model.Transition;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.SelfState;

/**
 * What playing one action until the next decision point did. These are
 * facts only; scoring them is the {@link Evaluator}'s job.
 *
 * @param damageTaken
 *            fall damage, before armor and effects
 * @param failure
 *            why the action failed, or {@code null}
 * @param chance
 *            that it works out despite random projectile spread
 * @param missDamage
 *            fall damage if it doesn't work out because the pearl misses
 *            its catch, and no wind charge can cushion the landing
 */
public record Outcome(Action action, GameState start, GameState end,
	List<Transition> steps, EnumSet<Event> events, double damageDealt,
	double damageTaken, String failure, double chance, double missDamage)
{
	public static Outcome of(Level level, Action action, Rollout rollout)
	{
		EnumSet<Event> events = EnumSet.noneOf(Event.class);
		double dealt = 0;
		double taken = 0;
		for(Transition t : rollout.steps())
		{
			events.addAll(t.events());
			dealt += t.damageDealt();
			taken += t.fallDamage();
		}
		String failure =
			rollout.end() instanceof Failed failed ? failed.reason() : null;
		return new Outcome(action, rollout.start(), rollout.last(),
			rollout.steps(), events, dealt, taken, failure, rollout.chance(),
			missDamage(level, rollout));
	}
	
	/**
	 * Where the catch's wind charge was thrown, the player falls on as if
	 * the pearl wasn't there.
	 */
	private static double missDamage(Level level, Rollout rollout)
	{
		if(rollout.chance() >= 1)
			return 0;
		
		boolean pearl = false;
		for(Transition t : rollout.steps())
		{
			pearl |= t.has(Event.THREW_PEARL);
			if(!pearl || !t.has(Event.THREW_WIND_CHARGE))
				continue;
			
			SelfState miss = t.next().self().withoutPearls();
			return Cushion.canCushion(level, miss) ? 0
				: Cushion.landingDamage(level, miss);
		}
		return 0;
	}
	
	public boolean failed()
	{
		return failure != null;
	}
	
	/** How many steps had {@code event}. */
	public int count(Event event)
	{
		return (int)steps.stream().filter(t -> t.events().contains(event))
			.count();
	}
	
	public int ticks()
	{
		return steps.size();
	}
	
	@Override
	public String toString()
	{
		return String.format("%s: %d ticks, dealt %.1f, took %.1f, p=%.2f%s",
			action.name(), ticks(), damageDealt, damageTaken, chance,
			failed() ? " FAILED (" + failure + ")" : "");
	}
}
