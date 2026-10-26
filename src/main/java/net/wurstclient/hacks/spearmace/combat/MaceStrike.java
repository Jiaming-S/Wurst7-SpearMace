/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.combat;

import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Channel;
import net.wurstclient.hacks.spearmace.action.Step;
import net.wurstclient.hacks.spearmace.action.Step.Run;
import net.wurstclient.hacks.spearmace.combat.Loadout.PreItem;
import net.wurstclient.hacks.spearmace.maneuver.SpearLunge;
import net.wurstclient.hacks.spearmace.model.TickInput;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.sim.StrikeModel;
import net.wurstclient.hacks.spearmace.sim.WindBurst;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.state.SelfState;

/**
 * An attribute-swapped mace hit while falling onto (or past) a target:
 * hold the pre-item while the attack charges and keep looking at the
 * target, then click the mace's hotbar key and the attack key in the same
 * tick. The hit uses the pre-item's damage and charge (and the spear's
 * reach, because the crosshair pick runs before the slot switch) together
 * with the mace's smash bonus and Wind Burst, which launches the player
 * back up.
 */
public record MaceStrike(PreItem pre) implements Action
{
	/**
	 * Updates between queuing the attack click and the Wind Burst launch
	 * showing up in the player's velocity, same as {@link SpearLunge}.
	 */
	public static final int LAUNCH_DELAY = SpearLunge.IMPULSE_DELAY;
	
	private static final int CONFIRM_TIMEOUT = LAUNCH_DELAY + 3;
	private static final int MAX_ATTEMPTS = 3;
	private static final int MAX_TICKS = Engagement.HORIZON;
	
	/**
	 * What the strike did.
	 *
	 * @param option
	 *            the plan at the moment of the attack click
	 * @param measuredLaunch
	 *            the y velocity after the launch
	 */
	public record Struck(PreItem pre, StrikeOption option,
		double expectedLaunch, double measuredLaunch, int attempts)
	{}
	
	@Override
	public String name()
	{
		return "MaceStrike(" + pre + ")";
	}
	
	@Override
	public Step next(Level level, GameState s)
	{
		String reason = checkPreconditions(s);
		if(reason != null)
			return Step.fail(reason);
		
		Kit kit = s.self().kit();
		int preSlot = kit.slotFor(pre);
		Track track = new Track(this, preSlot, kit.mace(), s.self().tick(), 0);
		Step step = track.next(level, s);
		if(kit.selected() != preSlot && step instanceof Run run)
			return run.withSlot(preSlot);
		return step;
	}
	
	/**
	 * The player's y velocity right after a Wind Burst smash with the
	 * player's feet at {@code feet}.
	 */
	public static double launchVelY(Level level, SelfState self, Vec3 feet)
	{
		Kit kit = self.kit();
		int windBurst =
			Loadout.level(kit.get(kit.mace()), Enchantments.WIND_BURST);
		double exposure =
			WindBurst.getSeenPercent(level, feet, PlayerSim.box(feet));
		return StrikeModel.windBurstVelY(windBurst, exposure,
			self.knockbackResistance());
	}
	
	private String checkPreconditions(GameState s)
	{
		Kit kit = s.self().kit();
		if(kit.mace() < 0)
			return "no mace";
		if(kit.slotFor(pre) < 0)
			return "no " + pre.name().toLowerCase();
		if(!s.targetAlive())
			return "target is dead";
		if(s.self().fallFlying())
			return "gliding";
		return null;
	}
	
	/** Charging the pre-item and aiming until the hit lands. */
	private record Track(MaceStrike strike, int preSlot, int maceSlot,
		int startTick, int attempts) implements Action
	{
		@Override
		public String name()
		{
			return strike.name();
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
			if(e.landing() == 0)
				return Step.fail("landed before the hit");
			
			TickInput aim = TickInput.NONE.withRotation(e.aim(1));
			Run wait = Step.run(aim, this).holding(Channel.HOTBAR);
			
			// wait for the slot switch and for a missed click's cooldown
			if(self.kit().selected() != preSlot || self.missTime() > 0)
				return wait;
				
			// predictions of the target's motion are noisy, so a missing
			// plan isn't final until the player lands
			StrikeOption option = StrikeSelector.plan(e, strike.pre());
			if(option == null && e.landing() <= 2)
				return Step.fail("no contact before landing: "
					+ e.describe(self.kit().get(preSlot), 20));
			if(option == null || option.update() > 0)
				return wait;
			
			double launch = launchVelY(level, self, e.selfPos(1));
			return Step.run(aim.withSlot(maceSlot).withAttack(),
				new Swung(this, option, self.tick(), attempts + 1, launch));
		}
	}
	
	/** Clicked the mace and attack, raising the pre-item again. */
	private record Swung(Track track, StrikeOption option, int strikeTick,
		int attempts, double expectedLaunch) implements Action
	{
		@Override
		public String name()
		{
			return track.name();
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			// handleKeybinds() has switched to the mace and attacked by now
			if(s.self().kit().selected() != track.maceSlot())
				return Step.fail("hotbar didn't switch to the mace");
			
			// raise the pre-item again for the next strike
			return Step.idle(new Confirm(this)).withSlot(track.preSlot())
				.asCommitted();
		}
	}
	
	/**
	 * Waiting for the Wind Burst launch, or trying again without it. The
	 * wait is committed: a hit close to the ground lands before the launch
	 * shows up, and a new plan made then would throw the launch away.
	 */
	private record Confirm(Swung swung) implements Action
	{
		@Override
		public String name()
		{
			return swung.name();
		}
		
		@Override
		public Step next(Level level, GameState s)
		{
			SelfState self = s.self();
			double velY = self.vel().y;
			if(velY > swung.expectedLaunch() / 2)
				return Step.done(
					new Struck(swung.track().strike().pre(), swung.option(),
						swung.expectedLaunch(), velY, swung.attempts()));
			
			if(self.tick() - swung.strikeTick() <= CONFIRM_TIMEOUT)
				return Step.idle(this).holding(Channel.HOTBAR).asCommitted();
			
			if(swung.attempts() >= MAX_ATTEMPTS)
				return Step.fail("no Wind Burst launch after "
					+ swung.attempts() + " attempts");
			Track t = swung.track();
			return Step.idle(new Track(t.strike(), t.preSlot(), t.maceSlot(),
				t.startTick(), swung.attempts())).holding(Channel.HOTBAR);
		}
	}
}
