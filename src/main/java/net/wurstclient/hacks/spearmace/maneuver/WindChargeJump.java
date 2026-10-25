/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.maneuver;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Channel;
import net.wurstclient.hacks.spearmace.action.Step;
import net.wurstclient.hacks.spearmace.action.Step.Run;
import net.wurstclient.hacks.spearmace.model.TickInput;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.util.Rotation;

/**
 * Throws a wind charge at the ground (straight down by default) to launch
 * the player upwards. Works standing or while moving, and can jump in the
 * same tick for extra height. Done at the top of the launch.
 *
 * @param yaw
 *            {@code null} to keep the current yaw
 * @param pitch
 *            90 throws straight down
 * @param forward
 *            whether to sprint forward through the throw and the launch,
 *            which carries the run-up into the air
 */
public record WindChargeJump(Float yaw, float pitch, boolean jumpFirst,
	boolean forward) implements Action
{
	public WindChargeJump(Float yaw, float pitch, boolean jumpFirst)
	{
		this(yaw, pitch, jumpFirst, false);
	}
	
	/** How long a charge may fly before the launch counts as failed. */
	private static final int MAX_FLIGHT = 12;
	
	/** What the launch did. */
	public record Launched(Vec3 burst)
	{}
	
	/** Straight down, keeping the current yaw. */
	public static WindChargeJump atFeet(boolean jumpFirst)
	{
		return new WindChargeJump(null, 90, jumpFirst);
	}
	
	/**
	 * A sprint-jump toward {@code yaw} with the charge straight down,
	 * holding forward until the apex.
	 */
	public static WindChargeJump forward(float yaw)
	{
		return new WindChargeJump(yaw, 90, true, true);
	}
	
	@Override
	public String name()
	{
		return String.format("WindChargeJump(pitch=%.1f, jump=%s%s)", pitch,
			jumpFirst, forward ? ", forward" : "");
	}
	
	@Override
	public Step next(Level level, GameState s)
	{
		SelfState self = s.self();
		int slot = self.kit().windCharge();
		if(slot < 0)
			return Step.fail("no wind charge ready");
		
		boolean jump = jumpFirst && self.onGround();
		Rotation aim = new Rotation(yaw == null ? self.yaw() : yaw, pitch);
		TickInput input =
			TickInput.NONE.withRotation(aim).withSlot(slot).withUse();
		Flying flying =
			new Flying(name(), self.kit().selected(), jump, 1, self, forward);
		Run run =
			jump ? Step.run(input.withJump(), flying).holding(Channel.JUMP)
				: Step.run(input, flying);
		return forward ? run.moving(true) : run;
	}
	
	/** Waiting for the charge to explode. */
	private record Flying(String name, int restoreSlot, boolean releaseJump,
		int ticks, SelfState last, boolean forward) implements Action
	{
		@Override
		public Step next(Level level, GameState s)
		{
			SelfState self = s.self();
			Vec3 burst = PlayerSim.push(level, last, self);
			Action then = burst.y > 0.2 ? new Rising(name, burst, forward)
				: new Flying(name, -1, false, ticks + 1, self, forward);
			if(then instanceof Flying && ticks > MAX_FLIGHT + 2)
				return Step.fail("no explosion");
			
			Run run = Step.idle(then);
			if(releaseJump)
				run = run.holding(Channel.JUMP);
			if(restoreSlot >= 0 && restoreSlot != self.kit().selected())
				run = run.withSlot(restoreSlot);
			return forward ? run.moving(true) : run;
		}
	}
	
	/** Going up until the apex. */
	private record Rising(String name, Vec3 burst, boolean forward)
		implements Action
	{
		@Override
		public Step next(Level level, GameState s)
		{
			// still on the ground right after the burst, so only check
			// velocity
			if(s.self().vel().y <= 0)
				return Step.done(new Launched(burst));
			Run run = Step.idle(this);
			return forward ? run.moving(true) : run;
		}
	}
}
