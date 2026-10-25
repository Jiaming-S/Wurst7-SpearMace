/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.maneuver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hacks.spearmace.maneuver.Maneuver.Status;

/**
 * Runs maneuvers one after another, each starting once its
 * {@link StartCondition} is met. If one fails, the rest of the queue is
 * dropped.
 */
public final class ManeuverRunner implements UpdateListener
{
	private static final Logger LOGGER = LoggerFactory.getLogger("SpearMace");
	private static final Minecraft MC = WurstClient.MC;
	
	private final ArrayDeque<Step> queue = new ArrayDeque<>();
	private final ArrayList<Result> results = new ArrayList<>();
	
	private Maneuver current;
	private Prediction prediction;
	private Vec3 startPos;
	private long startTick;
	private long lastEndTick = Long.MIN_VALUE;
	private long tick;
	
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
	}
	
	public void enqueue(Maneuver maneuver, StartCondition condition)
	{
		queue.add(new Step(maneuver, condition));
	}
	
	public void enqueue(Maneuver maneuver)
	{
		enqueue(maneuver, StartCondition.immediately());
	}
	
	public boolean isIdle()
	{
		return current == null && queue.isEmpty();
	}
	
	public List<Result> results()
	{
		return List.copyOf(results);
	}
	
	public long tick()
	{
		return tick;
	}
	
	/** Updates since the last maneuver ended. */
	public long ticksSinceLastEnd()
	{
		return tick - lastEndTick;
	}
	
	@Override
	public void onUpdate()
	{
		tick++;
		
		if(current == null && !startNext())
			return;
		
		Status status = current.tick(tick);
		if(status != Status.RUNNING)
			finish(status, current.failureReason());
	}
	
	private boolean startNext()
	{
		Step next = queue.peek();
		if(next == null || !next.condition().test(this))
			return false;
		
		queue.poll();
		current = next.maneuver();
		startTick = tick;
		startPos = MC.player.position();
		prediction = current.predict();
		LOGGER.info("[{}] start {} at {}, predicted {}", tick, current.name(),
			Prediction.fmt(startPos), prediction);
		
		if(!prediction.feasible())
		{
			finish(Status.FAILED, "infeasible: " + prediction.reason());
			return false;
		}
		
		return true;
	}
	
	private void finish(Status status, String reason)
	{
		Result result = new Result(current.name(), status, reason, prediction,
			startPos, MC.player.position(), startTick, tick);
		results.add(result);
		LOGGER.info("[{}] {}", tick, result);
		
		current = null;
		lastEndTick = tick;
		if(status == Status.FAILED)
			queue.clear();
	}
	
	public record Result(String name, Status status, String reason,
		Prediction prediction, Vec3 startPos, Vec3 endPos, long startTick,
		long endTick)
	{
		@Override
		public String toString()
		{
			return String.format(
				"%s %s%s: start=%s end=%s ticks=%d, predicted %s", name, status,
				reason == null ? "" : " (" + reason + ")",
				Prediction.fmt(startPos), Prediction.fmt(endPos),
				endTick - startTick, prediction);
		}
	}
	
	private record Step(Maneuver maneuver, StartCondition condition)
	{}
	
	@FunctionalInterface
	public interface StartCondition
	{
		public boolean test(ManeuverRunner runner);
		
		public static StartCondition immediately()
		{
			return runner -> true;
		}
		
		/** Waits this many updates after the previous maneuver ended. */
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
