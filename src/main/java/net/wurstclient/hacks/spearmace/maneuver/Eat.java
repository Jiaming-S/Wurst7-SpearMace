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
import net.wurstclient.hacks.spearmace.action.Channel;
import net.wurstclient.hacks.spearmace.action.Step;
import net.wurstclient.hacks.spearmace.action.Step.Run;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.state.Pending.Ate;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.hacks.spearmace.state.Vitals;
import net.wurstclient.hacks.spearmace.state.Vitals.Apple;

/**
 * Eats a golden apple: selects it, clicks and holds the use key, and lets
 * go just before the server finishes, so that the held key doesn't start
 * another one. Keeps eating if something else started it. Done once the
 * apple is eaten.
 */
public record Eat() implements Action
{
	/**
	 * Apples that add less than this much {@link Vitals#effective()} health
	 * aren't worth eating.
	 */
	public static final double MIN_VALUE = 2;
	
	/** Ticks left at which stopping would waste most of the eating. */
	private static final int COMMIT_TICKS = 8;
	
	/** The apple was eaten. */
	public record Eaten(Apple apple)
	{}
	
	@Override
	public String name()
	{
		return "Eat";
	}
	
	@Override
	public Step next(Level level, GameState s)
	{
		SelfState self = s.self();
		if(self.eating())
			return new Eating(0, self.useTicks()).next(level, s);
		
		int slot = self.kit().goldenApple();
		if(slot < 0)
			return Step.fail("no golden apple ready");
		return Step.idle(new Eating(1, -1)).eating(self, slot);
	}
	
	/**
	 * Whether eating the apple that {@link Eat} would pick is worth it now.
	 * Not while already eating, or before the last apple's effects show up.
	 */
	public static boolean worthEating(SelfState self)
	{
		if(self.eating() || !self.pending(Ate.class).isEmpty())
			return false;
		int slot = self.kit().goldenApple();
		return slot >= 0 && Kit.apple(self.kit().get(slot))
			.value(self.vitals()) >= MIN_VALUE;
	}
	
	/**
	 * The step that keeps eating going for an action that is otherwise
	 * idle: holds the use key and the slot, and lets go of the key on the
	 * update before the server finishes.
	 */
	public static Run keepEating(SelfState self, Action then)
	{
		Run run = Step.idle(then).holdingUse(self.useTicks() > 1)
			.holding(Channel.HOTBAR);
		return self.useTicks() <= COMMIT_TICKS ? run.asCommitted() : run;
	}
	
	/**
	 * Holding the use key until the apple is eaten.
	 *
	 * @param lastUseTicks
	 *            ticks of eating left at the last update, or -1 before
	 *            eating was seen to start
	 */
	private record Eating(int ticks, int lastUseTicks) implements Action
	{
		@Override
		public String name()
		{
			return "Eat";
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			SelfState self = s.self();
			if(!self.eating())
			{
				// the server finishes it on the last tick
				if(lastUseTicks == 1)
					return Step.done(new Eaten(Kit.apple(self.kit().held())));
				return Step.fail(lastUseTicks < 0 ? "eating didn't start"
					: "eating stopped");
			}
			if(ticks > Apple.EAT_TICKS + 2)
				return Step.fail("eating takes too long");
			
			return keepEating(self, new Eating(ticks + 1, self.useTicks()));
		}
	}
}
