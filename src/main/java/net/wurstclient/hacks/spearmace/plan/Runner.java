/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Step;
import net.wurstclient.hacks.spearmace.action.Step.Done;
import net.wurstclient.hacks.spearmace.action.Step.Failed;
import net.wurstclient.hacks.spearmace.action.Step.Run;
import net.wurstclient.hacks.spearmace.input.KeyInput;
import net.wurstclient.hacks.spearmace.model.Tracker;
import net.wurstclient.hacks.spearmace.model.Transition;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.viz.Preview;

/**
 * Plays the fight: runs the plan's actions and asks the {@link Planner}
 * for a new plan when the old one is done or a {@link Trigger} fires while
 * the current action isn't committed.
 */
public final class Runner implements UpdateListener
{
	private static final Logger LOGGER = LoggerFactory.getLogger("SpearMace");
	private static final Minecraft MC = WurstClient.MC;
	
	private final Supplier<LivingEntity> target;
	private Planner planner;
	private final Tracker tracker = new Tracker();
	private final ArrayList<Entry> history = new ArrayList<>();
	
	private GameState previous;
	private Execution execution = new Execution(Plan.NOTHING, 0);
	private int replans;
	
	/**
	 * @param target
	 *            the entity to fight, or {@code null} if there is none
	 */
	public Runner(Supplier<LivingEntity> target, Planner planner)
	{
		this.target = target;
		this.planner = planner;
	}
	
	/** Uses {@code planner} from the next replan on. */
	public void setPlanner(Planner planner)
	{
		this.planner = planner;
	}
	
	public void start()
	{
		WurstClient.INSTANCE.getEventManager().add(UpdateListener.class, this);
	}
	
	public void stop()
	{
		WurstClient.INSTANCE.getEventManager().remove(UpdateListener.class,
			this);
		tracker.reset();
		previous = null;
		execution = new Execution(Plan.NOTHING, 0);
	}
	
	@Override
	public void onUpdate()
	{
		GameState now = tracker.observe(target.get());
		EnumSet<Trigger> triggers = Triggers.detect(previous, now,
			tracker.predicted(), execution.planned(now.self().tick()));
		if(!triggers.isEmpty())
			execution.interrupt(MC.level, now, triggers);
		if(execution.isDone())
			replan(now, triggers);
		
		Step step = execution.step(MC.level, now);
		if(execution.isDone())
		{
			replan(now, EnumSet.noneOf(Trigger.class));
			step = execution.step(MC.level, now);
		}
		
		Run run = step instanceof Run r ? r : Step.idle(null);
		run = planner.controller().fill(now, run);
		KeyInput.apply(run.input(), run.claims());
		tracker.endUpdate();
		previous = now;
	}
	
	private void replan(GameState now, EnumSet<Trigger> triggers)
	{
		Plan plan = planner.replan(MC.level, now);
		replans++;
		if(!triggers.isEmpty())
			LOGGER.info("[{}] replanned on {}", now.self().tick(), triggers);
		if(!plan.expected().isEmpty())
			Preview.show(plan.toString(), now,
				plan.expected().getFirst().steps());
		execution = new Execution(plan, now.self().tick());
	}
	
	/** Every action that ended so far, oldest first. */
	public List<Entry> history()
	{
		return List.copyOf(history);
	}
	
	public int replans()
	{
		return replans;
	}
	
	public enum Status
	{
		DONE,
		FAILED,
		
		/** A decision point came up and the plan changed. */
		INTERRUPTED;
	}
	
	/**
	 * @param payload
	 *            what a successful action measured, see {@link Done}
	 */
	public record Entry(String name, Status status, String reason,
		Object payload, int startTick, int endTick)
	{
		public <T> T payload(Class<T> type)
		{
			return type.isInstance(payload) ? type.cast(payload) : null;
		}
		
		@Override
		public String toString()
		{
			return String.format("%s %s%s ticks %d-%d%s", name, status,
				reason == null ? "" : " (" + reason + ")", startTick, endTick,
				payload == null ? "" : ": " + payload);
		}
	}
	
	/** Where the runner is in a plan. */
	private final class Execution
	{
		private final Plan plan;
		private final int planTick;
		private int index;
		private Action current;
		private String name;
		private int startTick;
		private boolean committed;
		
		private Execution(Plan plan, int tick)
		{
			this.plan = plan;
			planTick = tick;
			startTick = tick;
			if(!plan.actions().isEmpty())
			{
				current = plan.actions().getFirst();
				name = current.name();
			}
		}
		
		/**
		 * What the plan expected at {@code tick}, as long as its first
		 * action runs, or {@code null}.
		 */
		private GameState planned(int tick)
		{
			if(current == null || index > 0 || plan.expected().isEmpty())
				return null;
			List<Transition> steps = plan.expected().getFirst().steps();
			int i = tick - planTick - 1;
			return i >= 0 && i < steps.size() ? steps.get(i).next() : null;
		}
		
		private boolean isDone()
		{
			return current == null;
		}
		
		/**
		 * Lets the current action see {@code now} first, so that one
		 * that just finished says so, then drops the rest of the plan
		 * unless the action is committed.
		 */
		private void interrupt(Level level, GameState now,
			EnumSet<Trigger> triggers)
		{
			if(current == null || committed)
				return;
			
			Step look = current.next(level, now);
			int tick = now.self().tick();
			switch(look)
			{
				case Done done -> end(Status.DONE, null, done.result(), tick);
				case Failed failed -> end(Status.FAILED, failed.reason(), null,
					tick);
				case Run run -> end(Status.INTERRUPTED, triggers.toString(),
					null, tick);
			}
			current = null;
		}
		
		private Step step(Level level, GameState now)
		{
			int tick = now.self().tick();
			while(current != null)
			{
				Step step = current.next(level, now);
				switch(step)
				{
					case Run run ->
					{
						current = run.then();
						committed = run.committed();
						return run;
					}
					case Done done -> end(Status.DONE, null, done.result(),
						tick);
					case Failed failed -> end(Status.FAILED, failed.reason(),
						null, tick);
				}
				
				// a finished action hands over right away
				if(failedLast() || ++index >= plan.actions().size())
					current = null;
				else
				{
					current = plan.actions().get(index);
					name = current.name();
					startTick = tick;
				}
			}
			return Step.idle(null);
		}
		
		private boolean failedLast()
		{
			return !history.isEmpty()
				&& history.getLast().status() == Status.FAILED;
		}
		
		private void end(Status status, String reason, Object payload, int tick)
		{
			Entry entry =
				new Entry(name, status, reason, payload, startTick, tick);
			history.add(entry);
			committed = false;
			LOGGER.info("[{}] {}", tick, entry);
		}
	}
}
