/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.maneuver;

import net.minecraft.world.level.Level;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Step;
import net.wurstclient.hacks.spearmace.action.Step.Run;
import net.wurstclient.hacks.spearmace.model.Event;
import net.wurstclient.hacks.spearmace.model.TargetModel;
import net.wurstclient.hacks.spearmace.model.TickInput;
import net.wurstclient.hacks.spearmace.model.TickSim;
import net.wurstclient.hacks.spearmace.model.Transition;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.util.Rotation;

/**
 * Throws a wind charge straight down just before landing, so that the
 * server only counts the fall below the explosion for fall damage, which
 * is too short to hurt. Not needed after a wind charge launch or a smash,
 * which protect the landing the same way.
 *
 * <p>
 * Finds the ticks a throw works in, throws near the end of them, and is
 * done on the ground.
 */
public record Cushion() implements Action
{
	/** How far ahead to look for the landing. */
	private static final int HORIZON = 80;
	
	/**
	 * Extra ticks of waiting that must still work before waiting, for clicks
	 * that the server handles a tick late.
	 */
	private static final int SAFETY_TICKS = 1;
	
	/**
	 * The charge has to explode less than 3 blocks above the ground, which
	 * it reaches within a few ticks of the throw, so a throw this long
	 * before the landing is never needed yet.
	 */
	private static final int MAX_LEAD = 10;
	
	/** The landing after the throw. */
	public record Cushioned()
	{}
	
	@Override
	public String name()
	{
		return "Cushion";
	}
	
	@Override
	public Step next(Level level, GameState s)
	{
		SelfState self = s.self();
		if(self.onGround())
			return Step.fail("on the ground");
		if(!self.kit().hasWindCharge())
			return Step.fail("no wind charge");
		if(landingDamage(level, self) <= 0)
			return Step.fail("lands safely without it");
		
		int wait = lastSafeWait(level, self);
		if(wait < 0)
			return Step.fail("no throw cushions the landing");
		int throwTick = self.tick() + Math.max(0, wait - SAFETY_TICKS);
		return new Waiting(self.kit().selected(), throwTick).next(level, s);
	}
	
	/**
	 * Landing from {@code self} without doing anything.
	 *
	 * @param ticks
	 *            steps until the landing
	 */
	public record Landing(int ticks, double fallDamage)
	{}
	
	/** @return the landing, or {@code null} if it isn't soon */
	public static Landing landing(Level level, SelfState self)
	{
		TickSim sim = sim(level);
		GameState state = new GameState(self, null);
		for(int i = 0; i < HORIZON; i++)
		{
			Transition t = sim.step(state, held(state.self()));
			if(t.has(Event.LANDED))
				return new Landing(i + 1, t.fallDamage());
			state = t.next();
		}
		return null;
	}
	
	/**
	 * The fall damage that landing from {@code self} costs without doing
	 * anything, or 0 if it doesn't land soon.
	 */
	public static double landingDamage(Level level, SelfState self)
	{
		Landing landing = landing(level, self);
		return landing == null ? 0 : landing.fallDamage();
	}
	
	/**
	 * Whether throwing straight down after {@code wait} more ticks
	 * explodes the charge before the landing, and the landing doesn't hurt.
	 */
	public static boolean landsSafely(Level level, SelfState self, int wait)
	{
		TickSim sim = sim(level);
		GameState state = new GameState(self, null);
		boolean thrown = false;
		boolean exploded = false;
		for(int i = 0; i < HORIZON; i++)
		{
			TickInput input = held(state.self());
			if(i == wait)
			{
				int slot = state.self().kit().windCharge();
				if(slot < 0)
					return false;
				input = throwInput(state.self(), slot);
			}
			
			Transition t = sim.step(state, input);
			thrown |= t.has(Event.THREW_WIND_CHARGE);
			exploded |= thrown && t.has(Event.EXPLODED);
			if(t.has(Event.LANDED))
				return exploded && t.fallDamage() <= 0;
			state = t.next();
		}
		return false;
	}
	
	/** Whether a throw at some point before the landing works. */
	public static boolean canCushion(Level level, SelfState self)
	{
		return lastSafeWait(level, self) >= 0;
	}
	
	/**
	 * The longest wait before a throw that still lands safely, or -1 if
	 * none does.
	 */
	private static int lastSafeWait(Level level, SelfState self)
	{
		Landing landing = landing(level, self);
		if(landing == null)
			return -1;
		for(int wait = landing.ticks() - 1; wait >= 0
			&& wait >= landing.ticks() - MAX_LEAD; wait--)
			if(landsSafely(level, self, wait))
				return wait;
		return -1;
	}
	
	/**
	 * Whether the throw has to happen now: it works now, but not after
	 * waiting the safety margin longer.
	 */
	public static boolean throwNow(Level level, SelfState self)
	{
		Landing landing = landing(level, self);
		if(landing == null || landing.ticks() > MAX_LEAD)
			return false;
		return landsSafely(level, self, 0)
			&& !landsSafely(level, self, 1 + SAFETY_TICKS);
	}
	
	/** A throw straight down with the wind charge in {@code slot}. */
	public static TickInput throwInput(SelfState self, int slot)
	{
		return TickInput.NONE.withRotation(new Rotation(self.yaw(), 90))
			.withSlot(slot).withUse();
	}
	
	private static TickSim sim(Level level)
	{
		return new TickSim(level, TargetModel.ballistic());
	}
	
	/** Nothing pressed, the held keys kept. */
	private static TickInput held(SelfState self)
	{
		return new TickInput(null, self.move(), self.sprinting(), self.jump(),
			-1, false, false, self.eating());
	}
	
	/**
	 * Falling until the last tick a throw still works, checking again from
	 * each observed state as the throw comes closer.
	 *
	 * @param throwTick
	 *            when the throw was planned for
	 */
	private record Waiting(int restoreSlot, int throwTick) implements Action
	{
		@Override
		public String name()
		{
			return "Cushion";
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			SelfState self = s.self();
			int tick = self.tick();
			if(self.onGround())
				return Step.fail("landed before the throw");
			
			int slot = self.kit().windCharge();
			boolean due = tick >= throwTick;
			if(slot >= 0 && tick >= throwTick - SAFETY_TICKS
				&& (due && landsSafely(level, self, 0)
					|| throwNow(level, self)))
				return Step
					.run(throwInput(self, slot), new Thrown(restoreSlot, 1))
					.asCommitted();
			if(tick > throwTick + SAFETY_TICKS)
				return Step.fail("too late to cushion the landing");
			return Step.idle(this);
		}
	}
	
	/** Waiting for the landing. */
	private record Thrown(int restoreSlot, int ticks) implements Action
	{
		@Override
		public String name()
		{
			return "Cushion";
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			SelfState self = s.self();
			if(self.onGround())
				return Step.done(new Cushioned());
			if(ticks > HORIZON)
				return Step.fail("no landing");
			
			Run run = Step.idle(new Thrown(-1, ticks + 1));
			if(restoreSlot >= 0 && restoreSlot != self.kit().selected())
				run = run.withSlot(restoreSlot);
			return run.asCommitted();
		}
	}
}
