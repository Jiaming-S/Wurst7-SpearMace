/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.maneuver;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Channel;
import net.wurstclient.hacks.spearmace.action.Step;
import net.wurstclient.hacks.spearmace.model.TickInput;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.util.Rotation;

/**
 * Lunges with a Lunge-enchanted spear without paying for the spear's attack
 * cooldown: while holding a non-weapon slot, clicks the spear's hotbar key
 * and the attack key in the same tick, then switches back next tick.
 *
 * <p>
 * This works because {@code Minecraft.handleKeybinds()} switches the slot
 * before handling the attack click, but the attack speed attribute only
 * changes in the player tick afterwards. So the client and the server both
 * check the attack charge against the old item's attack speed. The lunge
 * pushes the player horizontally by {@code 0.458 * level * cos(pitch)}
 * along the view direction: flat looks lunge the furthest.
 */
public record SpearLunge(float yaw, float pitch) implements Action
{
	public static final float IMPULSE_PER_LEVEL = 0.458F;
	
	/**
	 * Client ticks between queuing the attack click and the impulse showing
	 * up in the player's velocity: the click is sent next tick, and the
	 * server's motion update lands after the tick after that.
	 */
	public static final int IMPULSE_DELAY = 3;
	
	private static final int MAX_WAIT = 40;
	private static final int CONFIRM_TIMEOUT = IMPULSE_DELAY + 2;
	
	/**
	 * The server sometimes ticks between the slot change and the attack, so
	 * it checks the spear's attack speed and ignores the attack. That can't
	 * be prevented with key presses, so failed lunges are retried.
	 */
	private static final int MAX_ATTEMPTS = 3;
	
	/**
	 * What the lunge did.
	 *
	 * @param measured
	 *            the horizontal push that really happened
	 */
	public record Lunged(Vec3 expected, Vec3 measured, int attempts)
	{}
	
	@Override
	public String name()
	{
		return String.format("SpearLunge(yaw=%.1f, pitch=%.1f)", yaw, pitch);
	}
	
	/** The horizontal push from a Lunge spear of the given level. */
	public static Vec3 impulse(float yaw, float pitch, int level)
	{
		Vec3 look = new Rotation(yaw, pitch).toLookVec();
		return new Vec3(look.x, 0, look.z).scale(IMPULSE_PER_LEVEL * level);
	}
	
	@Override
	public Step next(Level level, GameState s)
	{
		SelfState self = s.self();
		String reason = checkPreconditions(self);
		if(reason != null)
			return Step.fail(reason);
		
		Kit kit = self.kit();
		Wait wait =
			new Wait(this, kit.lungeSpear(), kit.hand(), self.tick(), 0);
		if(kit.selected() != wait.handSlot())
			return Step.idle(wait).withSlot(wait.handSlot());
		return wait.next(level, s);
	}
	
	private static String checkPreconditions(SelfState self)
	{
		if(self.kit().lungeSpear() < 0)
			return "no spear with Lunge";
		if(self.kit().hand() < 0)
			return "no hotbar slot without a weapon or tool";
		if(self.fallFlying())
			return "gliding";
		return null;
	}
	
	/** Holding the hand slot until its attack charge allows the jab. */
	private record Wait(SpearLunge lunge, int spearSlot, int handSlot,
		int since, int attempts) implements Action
	{
		@Override
		public String name()
		{
			return lunge.name();
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			SelfState self = s.self();
			if(self.tick() - since > MAX_WAIT)
				return Step.fail("attack charge never filled up");
			
			ItemStack spear = self.kit().get(spearSlot);
			if(self.kit().selected() != handSlot
				|| !self.canAttackNextTick(spear))
				return Step.idle(this).holding(Channel.HOTBAR);
			
			String reason = checkPreconditions(self);
			if(reason != null)
				return Step.fail(reason);
			
			Vec3 impulse =
				impulse(lunge.yaw(), lunge.pitch(), Hotbar.lungeLevel(spear));
			TickInput jab = TickInput.NONE
				.withRotation(new Rotation(lunge.yaw(), lunge.pitch()))
				.withSlot(spearSlot).withAttack();
			return Step.run(jab,
				new Stabbed(this, self.tick(), attempts + 1, impulse));
		}
	}
	
	/** Jabbed, switching back to the hand slot. */
	private record Stabbed(Wait waited, int stabTick, int attempts,
		Vec3 impulse) implements Action
	{
		@Override
		public String name()
		{
			return waited.name();
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			// handleKeybinds() has switched to the spear and stabbed by now
			if(s.self().kit().selected() != waited.spearSlot())
				return Step.fail("hotbar didn't switch to the spear");
			
			return Step.idle(new Confirm(this, s.self()))
				.withSlot(waited.handSlot());
		}
	}
	
	/** Waiting for the server's push. */
	private record Confirm(Stabbed stab, SelfState last) implements Action
	{
		@Override
		public String name()
		{
			return stab.name();
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			SelfState self = s.self();
			Vec3 impulse = stab.impulse();
			Vec3 push = PlayerSim.push(level, last, self);
			if(push.dot(impulse.normalize()) >= 0.6 * impulse.length())
				return Step.done(new Lunged(impulse,
					new Vec3(push.x, 0, push.z), stab.attempts()));
			
			if(self.tick() - stab.stabTick() <= CONFIRM_TIMEOUT)
				return Step.idle(new Confirm(stab, self))
					.holding(Channel.HOTBAR);
			
			if(stab.attempts() >= MAX_ATTEMPTS)
				return Step.fail(
					"no lunge impulse after " + stab.attempts() + " attempts");
			Wait wait = stab.waited();
			return Step.idle(new Wait(wait.lunge(), wait.spearSlot(),
				wait.handSlot(), self.tick(), stab.attempts()))
				.holding(Channel.HOTBAR);
		}
	}
}
