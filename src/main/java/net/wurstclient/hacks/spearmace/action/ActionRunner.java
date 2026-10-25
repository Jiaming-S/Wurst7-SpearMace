/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.action;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hacks.spearmace.action.Step.Done;
import net.wurstclient.hacks.spearmace.action.Step.Failed;
import net.wurstclient.hacks.spearmace.action.Step.Run;
import net.wurstclient.hacks.spearmace.input.KeyInput;
import net.wurstclient.hacks.spearmace.model.Observer;
import net.wurstclient.hacks.spearmace.model.Tracker;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.viz.Preview;

/**
 * Runs actions one after another, each starting once its
 * {@link StartCondition} is met. If one fails, the rest of the queue is
 * dropped.
 *
 * <p>
 * Every update, the runner tracks the game with the forward model (see
 * {@link Observer}), so actions see the clicks the server hasn't handled
 * yet. Before an action starts, its {@link Rollout} is the prediction, and
 * an action whose rollout fails doesn't start at all.
 */
public final class ActionRunner implements UpdateListener
{
	private static final Logger LOGGER = LoggerFactory.getLogger("SpearMace");
	private static final Minecraft MC = WurstClient.MC;
	private static final int MAX_ROLLOUT = 200;
	
	private final Supplier<LivingEntity> target;
	private final ArrayDeque<Queued> queue = new ArrayDeque<>();
	private final ArrayList<Result> results = new ArrayList<>();
	
	private final Tracker tracker = new Tracker();
	private GameState observed;
	
	private String name;
	private Action current;
	private Step first;
	private Prediction prediction;
	private Vec3 startPos;
	private long startTick;
	private long lastEndTick = Long.MIN_VALUE;
	private long tick;
	
	/**
	 * @param target
	 *            the entity to fight, or {@code null} if there is none
	 */
	public ActionRunner(Supplier<LivingEntity> target)
	{
		this.target = target;
	}
	
	public void start()
	{
		WurstClient.INSTANCE.getEventManager().add(UpdateListener.class, this);
	}
	
	public void stop()
	{
		WurstClient.INSTANCE.getEventManager().remove(UpdateListener.class,
			this);
		queue.clear();
		current = null;
		tracker.reset();
	}
	
	public void enqueue(Action action, StartCondition condition)
	{
		queue.add(new Queued(action, condition, true));
	}
	
	/**
	 * Runs {@code action} even if its rollout fails, for tests of what the
	 * game really does when the model expects a failure.
	 */
	public void enqueueAnyway(Action action)
	{
		queue.add(new Queued(action, StartCondition.immediately(), false));
	}
	
	public void enqueue(Action action)
	{
		enqueue(action, StartCondition.immediately());
	}
	
	public boolean isIdle()
	{
		return current == null && queue.isEmpty();
	}
	
	public List<Result> results()
	{
		return List.copyOf(results);
	}
	
	/** Updates since the last action ended. */
	public long ticksSinceLastEnd()
	{
		return tick - lastEndTick;
	}
	
	@Override
	public void onUpdate()
	{
		tick++;
		observed = tracker.observe(target.get());
		if(current != null || startNext())
			run();
		tracker.endUpdate();
	}
	
	private boolean startNext()
	{
		Queued next = queue.peek();
		if(next == null || !next.condition().test(this))
			return false;
		
		queue.poll();
		Action action = next.action();
		name = action.name();
		startTick = tick;
		startPos = observed.self().pos();
		Rollout rollout =
			Rollout.of(Tracker.sim(), observed, action, MAX_ROLLOUT);
		prediction = rollout.prediction();
		Preview.show(name, observed, rollout.steps());
		LOGGER.info("[{}] start {} at {}, predicted {}", tick, name,
			Prediction.fmt(startPos), prediction);
		
		if(next.checked() && !prediction.feasible())
		{
			finish(Status.FAILED, "infeasible: " + prediction.reason(), null);
			return false;
		}
		
		current = action;
		first = rollout.first();
		return true;
	}
	
	private void run()
	{
		Step step = first != null ? first : current.next(MC.level, observed);
		first = null;
		switch(step)
		{
			case Run run ->
			{
				KeyInput.apply(run.input(), run.claims());
				current = run.then();
			}
			case Done done -> finish(Status.DONE, null, done.result());
			case Failed failed -> finish(Status.FAILED, failed.reason(), null);
		}
	}
	
	private void finish(Status status, String reason, Object payload)
	{
		Result result = new Result(name, status, reason, prediction, startPos,
			MC.player.position(), startTick, tick, payload);
		results.add(result);
		LOGGER.info("[{}] {}", tick, result);
		current = null;
		lastEndTick = tick;
		if(status == Status.FAILED)
			queue.clear();
	}
	
	public enum Status
	{
		DONE,
		FAILED;
	}
	
	/**
	 * @param payload
	 *            what a successful action measured, see {@link Done}
	 */
	public record Result(String name, Status status, String reason,
		Prediction prediction, Vec3 startPos, Vec3 endPos, long startTick,
		long endTick, Object payload)
	{
		/** The payload as a {@code type}, or {@code null}. */
		public <T> T payload(Class<T> type)
		{
			return type.isInstance(payload) ? type.cast(payload) : null;
		}
		
		@Override
		public String toString()
		{
			return String.format(
				"%s %s%s: start=%s end=%s ticks=%d, predicted %s%s", name,
				status, reason == null ? "" : " (" + reason + ")",
				Prediction.fmt(startPos), Prediction.fmt(endPos),
				endTick - startTick, prediction,
				payload == null ? "" : ", " + payload);
		}
	}
	
	private record Queued(Action action, StartCondition condition,
		boolean checked)
	{}
	
	@FunctionalInterface
	public interface StartCondition
	{
		public boolean test(ActionRunner runner);
		
		public static StartCondition immediately()
		{
			return runner -> true;
		}
		
		/** Waits this many updates after the previous action ended. */
		public static StartCondition afterTicks(int ticks)
		{
			return runner -> runner.ticksSinceLastEnd() >= ticks;
		}
		
		/** Starts at the top of a jump (or immediately if falling). */
		public static StartCondition atApex()
		{
			return runner -> !MC.player.onGround()
				&& MC.player.getDeltaMovement().y <= 0;
		}
		
		public static StartCondition when(Predicate<LocalPlayer> condition)
		{
			return runner -> condition.test(MC.player);
		}
	}
}
