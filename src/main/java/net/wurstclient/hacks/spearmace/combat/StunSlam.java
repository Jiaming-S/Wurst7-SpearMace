/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.combat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Channel;
import net.wurstclient.hacks.spearmace.action.Step;
import net.wurstclient.hacks.spearmace.action.Step.Run;
import net.wurstclient.hacks.spearmace.model.TickInput;
import net.wurstclient.hacks.spearmace.sim.StrikeModel;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.state.SelfState;

/**
 * Breaks a raised shield and smashes through it: hit with the axe (its
 * charge doesn't matter), then swap to the mace and hit again on the next
 * tick.
 *
 * <p>
 * This takes two separate attacks. Only the item actually in hand can
 * disable a shield ({@code LivingEntity.getSecondsToDisableBlocking()}
 * checks {@code weaponItem == getActiveItem()}), and
 * {@code handleKeybinds()} handles all hotbar clicks before all attack
 * clicks, so the axe hit and the mace hit can't share a tick. The axe hit
 * is fully blocked, which leaves the target's {@code lastHurt} at 0, so the
 * mace hit still deals full damage during the hurt cooldown.
 */
public record StunSlam() implements Action
{
	private static final int CONFIRM_TIMEOUT = MaceStrike.LAUNCH_DELAY + 3;
	private static final int MAX_TICKS = Engagement.HORIZON;
	
	/**
	 * What the stunslam did.
	 *
	 * @param expectedDamage
	 *            of the mace hit, before armor
	 * @param measuredLaunch
	 *            the y velocity after the launch
	 * @param shieldWasDown
	 *            whether the client saw the shield lowered right after the
	 *            axe hit
	 */
	public record Slammed(double expectedDamage, double expectedFall,
		double expectedLaunch, double measuredLaunch, boolean shieldWasDown)
	{}
	
	@Override
	public String name()
	{
		return "StunSlam";
	}
	
	@Override
	public Step next(Level level, GameState s)
	{
		String reason = checkPreconditions(s);
		if(reason != null)
			return Step.fail(reason);
		
		Kit kit = s.self().kit();
		Track track = new Track(kit.axe(), kit.mace(), s.self().tick());
		Step step = track.next(level, s);
		if(kit.selected() != kit.axe() && step instanceof Run run)
			return run.withSlot(kit.axe());
		return step;
	}
	
	/**
	 * Whether the axe click can be queued at update {@code k}: the axe hit
	 * lands at {@code k + 1}, the mace hit at {@code k + 2}.
	 */
	public static boolean canStart(Engagement e, ItemStack axe, int k)
	{
		return e.inReach(axe, k + 1) && e.inReach(axe, k + 2)
			&& StrikeModel.canSmash(e.fall(k + 2) - 0.1);
	}
	
	private static String checkPreconditions(GameState s)
	{
		if(s.self().kit().mace() < 0)
			return "no mace";
		if(s.self().kit().axe() < 0)
			return "no axe";
		if(!s.targetAlive())
			return "target is dead";
		return null;
	}
	
	/** Holding the axe and aiming until both hits can land. */
	private record Track(int axeSlot, int maceSlot, int startTick)
		implements Action
	{
		@Override
		public String name()
		{
			return "StunSlam";
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			SelfState self = s.self();
			if(!s.targetAlive())
				return Step.fail("target is dead");
			if(self.tick() - startTick > MAX_TICKS)
				return Step.fail("took too long");
			
			Engagement e = Engagement.of(level, s);
			if(e.landing() <= 2)
				return Step.fail("landed before the hit");
			
			TickInput aim = TickInput.NONE.withRotation(e.aim(1));
			if(self.kit().selected() != axeSlot || self.missTime() > 0
				|| !canStart(e, self.kit().get(axeSlot), 0))
				return Step.run(aim, this).holding(Channel.HOTBAR);
			
			return Step.run(aim.withAttack(), new Axed(this, self.tick()))
				.holding(Channel.HOTBAR);
		}
	}
	
	/** Hit with the axe, now the mace. */
	private record Axed(Track track, int axeTick) implements Action
	{
		@Override
		public String name()
		{
			return "StunSlam";
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			SelfState self = s.self();
			Kit kit = self.kit();
			
			// a click that found nothing starts the miss cooldown
			if(self.missTime() > 0)
				return Step.idle(track).holding(Channel.HOTBAR);
				
			// the axe hit reset the charge, and the server's charge is a tick
			// behind, so this one is weak but still smashes
			Engagement e = Engagement.of(level, s);
			ItemStack axe = kit.get(track.axeSlot());
			double strength = StrikeModel.strength(0, kit.attackDelay(axe));
			double fall = e.fall(1);
			double damage = StrikeModel.damage(kit.attackDamage(axe), strength,
				fall,
				Loadout.level(kit.get(track.maceSlot()), Enchantments.DENSITY),
				false);
			double launch = MaceStrike.launchVelY(level, self, e.selfPos(1));
			TickInput hit = TickInput.NONE.withRotation(e.aim(1))
				.withSlot(track.maceSlot()).withAttack();
			return Step.run(hit, new Maced(this, damage, fall, launch));
		}
	}
	
	/** Hit with the mace, raising the axe again. */
	private record Maced(Axed axed, double expectedDamage, double expectedFall,
		double expectedLaunch) implements Action
	{
		@Override
		public String name()
		{
			return "StunSlam";
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			if(s.self().kit().selected() != axed.track().maceSlot())
				return Step.fail("hotbar didn't switch to the mace");
			
			boolean shieldWasDown =
				s.target() != null && !s.target().shieldRaised();
			return Step.idle(new Confirm(this, shieldWasDown))
				.withSlot(axed.track().axeSlot());
		}
	}
	
	/** Waiting for the Wind Burst launch. */
	private record Confirm(Maced maced, boolean shieldWasDown) implements Action
	{
		@Override
		public String name()
		{
			return "StunSlam";
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			double velY = s.self().vel().y;
			if(velY > maced.expectedLaunch() / 2)
				return Step.done(
					new Slammed(maced.expectedDamage(), maced.expectedFall(),
						maced.expectedLaunch(), velY, shieldWasDown));
			
			if(s.self().tick() - maced.axed().axeTick() > CONFIRM_TIMEOUT + 1)
				return Step.fail("no Wind Burst launch");
			return Step.idle(this).holding(Channel.HOTBAR);
		}
	}
}
