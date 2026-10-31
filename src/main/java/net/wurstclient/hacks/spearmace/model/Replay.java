/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.ToDoubleFunction;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.wurstclient.hacks.spearmace.model.Recorder.Frame;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Pending;
import net.wurstclient.hacks.spearmace.state.Pending.Impulse;
import net.wurstclient.hacks.spearmace.state.Pending.TargetDamage;
import net.wurstclient.hacks.spearmace.state.Pending.Teleport;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.hacks.spearmace.state.TargetState;

/**
 * Checks {@link TickSim} against recorded frames: step from each recorded
 * state with its recorded input and compare with the next recorded state.
 */
public enum Replay
{
	;
	
	/**
	 * How far one predicted step was off.
	 *
	 * @param index
	 *            the frame the step started from
	 * @param windChargeCooldown
	 *            1 if only the prediction is on cooldown, -1 if only the
	 *            real game is
	 * @param timing
	 *            whether the error is explained by something the model
	 *            expected arriving a tick early, late or never, which
	 *            depends on server timing that the client can't know, or
	 *            by a pearl teleporting somewhere else because of random
	 *            projectile spread
	 * @param health
	 *            predicted minus real health plus absorption
	 * @param useTicks
	 *            predicted minus real ticks left of eating
	 */
	public record StepError(int index, double pos, double vel, boolean slot,
		int ticker, double fall, int windChargeCooldown, int pearlCooldown,
		double targetPos, double targetHealth, double health, int useTicks,
		boolean timing, EnumSet<Event> events, TickInput input)
	{
		@Override
		public String toString()
		{
			return String.format(
				"#%d pos=%.4f vel=%.4f slot=%s ticker=%d fall=%.3f cd=%d/%d target=%.3f/%.2f health=%.2f use=%d%s %s %s",
				index, pos, vel, slot ? "ok" : "WRONG", ticker, fall,
				windChargeCooldown, pearlCooldown, targetPos, targetHealth,
				health, useTicks, timing ? " (timing)" : "", events, input);
		}
	}
	
	public record Report(List<StepError> steps)
	{
		public double max(ToDoubleFunction<StepError> error)
		{
			return steps.stream().mapToDouble(error).map(Math::abs).max()
				.orElse(0);
		}
		
		public double mean(ToDoubleFunction<StepError> error)
		{
			return steps.stream().mapToDouble(error).map(Math::abs).average()
				.orElse(0);
		}
		
		public long slotErrors()
		{
			return steps.stream().filter(s -> !s.slot()).count();
		}
		
		/** Steps where any error is above the given limits. */
		/** The steps that aren't explained by server timing. */
		public Report exact()
		{
			return new Report(steps.stream().filter(s -> !s.timing()).toList());
		}
		
		public long timingSteps()
		{
			return steps.stream().filter(StepError::timing).count();
		}
		
		public List<StepError> worse(double pos, double vel, int ticks)
		{
			return steps.stream()
				.filter(s -> s.pos() > pos || s.vel() > vel || !s.slot()
					|| Math.abs(s.ticker()) > ticks
					|| Math.abs(s.windChargeCooldown()) > ticks
					|| Math.abs(s.pearlCooldown()) > ticks
					|| Math.abs(s.health()) > 0.01 || s.useTicks() != 0)
				.toList();
		}
		
		public String summary()
		{
			return String.format(
				"%d steps (%d off by server timing): pos max=%.4f mean=%.5f, vel max=%.4f mean=%.5f, slot errors=%d, ticker max=%.0f, fall max=%.3f, cooldown max=%.0f/%.0f, target pos max=%.3f health max=%.2f, own health max=%.2f, eating max=%.0f",
				steps.size(), timingSteps(), max(StepError::pos),
				mean(StepError::pos), max(StepError::vel), mean(StepError::vel),
				slotErrors(), max(StepError::ticker), max(StepError::fall),
				max(StepError::windChargeCooldown),
				max(StepError::pearlCooldown), max(StepError::targetPos),
				max(StepError::targetHealth), max(StepError::health),
				max(StepError::useTicks));
		}
	}
	
	/**
	 * One step at a time from the recorded states, carrying over what the
	 * client can't see (see {@link Observer}).
	 */
	public static Report filtered(TickSim sim, List<Frame> frames)
	{
		ArrayList<StepError> steps = new ArrayList<>();
		GameState predicted = null;
		for(int i = 0; i + 1 < frames.size(); i++)
		{
			GameState from = Observer.observe(frames.get(i).state(), predicted);
			Transition t = sim.step(from, frames.get(i).input());
			steps.add(
				compare(i, from, t, frames.get(i + 1), frames.get(i).input()));
			predicted = t.next();
		}
		return new Report(steps);
	}
	
	/** All steps from the first recorded state, without corrections. */
	public static Report openLoop(TickSim sim, List<Frame> frames)
	{
		ArrayList<StepError> steps = new ArrayList<>();
		if(frames.isEmpty())
			return new Report(steps);
		
		GameState state = frames.get(0).state();
		for(int i = 0; i + 1 < frames.size(); i++)
		{
			Transition t = sim.step(state, frames.get(i).input());
			steps.add(
				compare(i, state, t, frames.get(i + 1), frames.get(i).input()));
			state = t.next();
		}
		return new Report(steps);
	}
	
	/**
	 * 1 while on cooldown. Only readiness is compared, as the client's own
	 * countdown restarts when the server's cooldown packet arrives.
	 */
	private static int readiness(SelfState state, Item item)
	{
		return state.kit().cooldownOf(item) > 0 ? 1 : 0;
	}
	
	private static StepError compare(int index, GameState from, Transition t,
		Frame actual, TickInput input)
	{
		SelfState p = t.next().self();
		SelfState a = actual.state().self();
		TargetState pt = t.next().target();
		TargetState at = actual.state().target();
		double targetPos =
			pt == null || at == null ? 0 : pt.pos().distanceTo(at.pos());
		double targetHealth =
			pt == null || at == null ? 0 : pt.health() - at.health();
		int windCharge =
			readiness(p, Items.WIND_CHARGE) - readiness(a, Items.WIND_CHARGE);
		int pearl =
			readiness(p, Items.ENDER_PEARL) - readiness(a, Items.ENDER_PEARL);
		
		ArrayList<Pending> window = new ArrayList<>(from.self().pending());
		window.addAll(p.pending());
		boolean timing = velocityTiming(window, p.vel().subtract(a.vel()))
			|| teleportTiming(window, p.pos(), a.pos())
			|| damageTiming(window, targetHealth)
			|| missedCatch(from.self(), p, a)
			|| windCharge != 0 && cooldownEnding(from, p, Items.WIND_CHARGE)
			|| pearl != 0 && cooldownEnding(from, p, Items.ENDER_PEARL);
		
		return new StepError(index, p.pos().distanceTo(a.pos()),
			p.vel().distanceTo(a.vel()),
			p.kit().selected() == a.kit().selected(),
			p.attackStrengthTicker() - a.attackStrengthTicker(),
			// the client doesn't reset it on smashes, see Observer
			Math.max(0, p.fallDistance() - a.fallDistance()), windCharge, pearl,
			targetPos, targetHealth, health(p) - health(a),
			p.useTicks() - a.useTicks(), timing, t.events(), input);
	}
	
	private static double health(SelfState s)
	{
		return s.vitals().health() + s.vitals().absorption();
	}
	
	/** A velocity change that one side has and the other doesn't. */
	private static boolean velocityTiming(List<Pending> window, Vec3 residual)
	{
		for(Pending p : window)
			if(p instanceof Impulse i)
			{
				if(!Double.isNaN(i.setY()) && Math.abs(residual.y) > 0.3)
					return true;
				if(Observer.explains(residual, i.add())
					|| Observer.explains(residual.scale(-1), i.add()))
					return true;
			}
		return false;
	}
	
	/** A teleport that only one side has made. */
	private static boolean teleportTiming(List<Pending> window, Vec3 predicted,
		Vec3 actual)
	{
		for(Pending p : window)
			if(p instanceof Teleport t && (predicted
				.distanceTo(t.to()) < 0.5) != (actual.distanceTo(t.to()) < 0.5))
				return true;
		return false;
	}
	
	/**
	 * A teleport on one side that the other doesn't have or has somewhere
	 * else: a pearl that random spread made miss its wind charge, or catch
	 * one it should have missed.
	 */
	private static boolean missedCatch(SelfState from, SelfState predicted,
		SelfState actual)
	{
		Vec3 moved = from.pos().add(from.vel());
		boolean jumped = predicted.pos().distanceTo(moved) > 2
			|| actual.pos().distanceTo(moved) > 2;
		return jumped && predicted.pos().distanceTo(actual.pos()) > 1;
	}
	
	/** Damage that only one side shows yet. */
	private static boolean damageTiming(List<Pending> window, double error)
	{
		for(Pending p : window)
			if(p instanceof TargetDamage d
				&& Math.abs(Math.abs(error) - d.damage()) < 1)
				return true;
		return false;
	}
	
	/** A cooldown that ends one tick earlier or later than predicted. */
	private static boolean cooldownEnding(GameState from, SelfState predicted,
		Item item)
	{
		return predicted.kit().cooldownOf(item) == 1
			|| from.self().kit().cooldownOf(item) == 1;
	}
}
