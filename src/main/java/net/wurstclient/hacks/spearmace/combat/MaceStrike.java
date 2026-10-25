/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.combat;

import java.util.ArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.combat.Loadout.PreItem;
import net.wurstclient.hacks.spearmace.input.KeyInput;
import net.wurstclient.hacks.spearmace.maneuver.Hotbar;
import net.wurstclient.hacks.spearmace.maneuver.Maneuver;
import net.wurstclient.hacks.spearmace.maneuver.Prediction;
import net.wurstclient.hacks.spearmace.maneuver.SpearLunge;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.sim.StrikeModel;
import net.wurstclient.hacks.spearmace.sim.WindBurst;

/**
 * An attribute-swapped mace hit while falling onto (or past) a target:
 * hold the pre-item while the attack charges and keep looking at the
 * target, then click the mace's hotbar key and the attack key in the same
 * tick. The hit uses the pre-item's damage and charge (and the spear's
 * reach, because the crosshair pick runs before the slot switch) together
 * with the mace's smash bonus and Wind Burst, which launches the player
 * back up.
 */
public final class MaceStrike implements Maneuver
{
	private static final Minecraft MC = WurstClient.MC;
	
	/**
	 * Updates between queuing the attack click and the Wind Burst launch
	 * showing up in the player's velocity, same as {@link SpearLunge}.
	 */
	public static final int LAUNCH_DELAY = SpearLunge.IMPULSE_DELAY;
	
	private static final int CONFIRM_TIMEOUT = LAUNCH_DELAY + 3;
	private static final int MAX_ATTEMPTS = 3;
	private static final int MAX_TICKS = Engagement.HORIZON;
	
	private final LivingEntity target;
	private final PreItem pre;
	
	private Phase phase = Phase.START;
	private String failureReason;
	private long startTick = -1;
	private long strikeTick;
	private int attempts;
	private int preSlot;
	private int maceSlot;
	private StrikeOption struck;
	private double expectedLaunch;
	private double measuredLaunch = Double.NaN;
	
	public MaceStrike(LivingEntity target, PreItem pre)
	{
		this.target = target;
		this.pre = pre;
	}
	
	@Override
	public String name()
	{
		return "MaceStrike(" + pre + ")";
	}
	
	@Override
	public Prediction predict()
	{
		String reason = checkPreconditions();
		if(reason != null)
			return Prediction.infeasible(reason);
		
		Engagement e = Engagement.now(target);
		StrikeOption option = StrikeSelector.plan(e, pre);
		if(option == null)
			return Prediction.infeasible("no contact before landing");
		
		// fall until the launch arrives, then rise to the apex
		PlayerSim sim = PlayerSim.ofLocalPlayer();
		ArrayList<Vec3> path = new ArrayList<>();
		path.add(sim.pos);
		Vec3 hitPos = e.selfPos(option.update() + 1);
		for(int i = 0; i < option.update() + LAUNCH_DELAY; i++)
		{
			sim.step();
			path.add(sim.pos);
		}
		
		sim.vel = new Vec3(sim.vel.x, launchVelY(hitPos), sim.vel.z);
		sim.onGround = false;
		for(int i = 0; i < 100 && sim.vel.y > 0; i++)
		{
			sim.step();
			path.add(sim.pos);
		}
		return Prediction.of(path, sim.vel, 1);
	}
	
	/**
	 * The player's y velocity right after a Wind Burst smash with the
	 * player's feet at {@code feet}.
	 */
	public static double launchVelY(Vec3 feet)
	{
		int level =
			Loadout.level(Hotbar.get(Loadout.mace()), Enchantments.WIND_BURST);
		double exposure =
			WindBurst.getSeenPercent(MC.level, feet, PlayerSim.box(feet));
		double resistance = MC.player
			.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE);
		return StrikeModel.windBurstVelY(level, exposure, resistance);
	}
	
	private String checkPreconditions()
	{
		if(Loadout.mace() < 0)
			return "no mace";
		if(Loadout.slotFor(pre) < 0)
			return "no " + pre.name().toLowerCase();
		if(!target.isAlive())
			return "target is dead";
		if(MC.player.isFallFlying())
			return "gliding";
		return null;
	}
	
	@Override
	public Status tick(long tick)
	{
		if(startTick < 0)
			startTick = tick;
		
		return switch(phase)
		{
			case START -> start(tick);
			case TRACK -> track(tick);
			case STRUCK -> recover(tick);
			case CONFIRM -> confirm(tick);
		};
	}
	
	private Status start(long tick)
	{
		failureReason = checkPreconditions();
		if(failureReason != null)
			return Status.FAILED;
		
		preSlot = Loadout.slotFor(pre);
		maceSlot = Loadout.mace();
		if(Hotbar.selected() != preSlot)
			KeyInput.selectSlot(preSlot);
		
		phase = Phase.TRACK;
		return track(tick);
	}
	
	private Status track(long tick)
	{
		if(!target.isAlive())
			return fail("target is dead");
		if(tick - startTick > MAX_TICKS)
			return fail("took too long");
		
		Engagement e = Engagement.now(target);
		if(e.landing() == 0)
			return fail("landed before the hit");
		
		KeyInput.look(e.aim(1));
		
		// wait for the slot switch and for a missed click's cooldown
		if(Hotbar.selected() != preSlot || MC.missTime > 0)
			return Status.RUNNING;
			
		// predictions of the target's motion are noisy, so a missing plan
		// isn't final until the player lands
		StrikeOption option = StrikeSelector.plan(e, pre);
		if(option == null && e.landing() <= 2)
			return fail("no contact before landing: "
				+ e.describe(Hotbar.get(preSlot), 20));
		if(option == null || option.update() > 0)
			return Status.RUNNING;
		
		KeyInput.selectSlot(maceSlot);
		KeyInput.clickAttack();
		struck = option;
		strikeTick = tick;
		attempts++;
		expectedLaunch = launchVelY(e.selfPos(1));
		phase = Phase.STRUCK;
		return Status.RUNNING;
	}
	
	private Status recover(long tick)
	{
		// handleKeybinds() has switched to the mace and attacked by now
		if(Hotbar.selected() != maceSlot)
			return fail("hotbar didn't switch to the mace");
		
		// raise the pre-item again for the next strike
		KeyInput.selectSlot(preSlot);
		phase = Phase.CONFIRM;
		return confirm(tick);
	}
	
	private Status confirm(long tick)
	{
		double velY = MC.player.getDeltaMovement().y;
		if(velY > expectedLaunch / 2)
		{
			measuredLaunch = velY;
			return Status.DONE;
		}
		
		if(tick - strikeTick > CONFIRM_TIMEOUT)
		{
			if(attempts >= MAX_ATTEMPTS)
				return fail(
					"no Wind Burst launch after " + attempts + " attempts");
			
			phase = Phase.TRACK;
		}
		
		return Status.RUNNING;
	}
	
	/** The plan at the moment of the attack click, or {@code null}. */
	public StrikeOption struck()
	{
		return struck;
	}
	
	public double expectedLaunchVelY()
	{
		return expectedLaunch;
	}
	
	/** The y velocity after the launch, or NaN. */
	public double measuredLaunchVelY()
	{
		return measuredLaunch;
	}
	
	public int attempts()
	{
		return attempts;
	}
	
	public PreItem pre()
	{
		return pre;
	}
	
	private Status fail(String reason)
	{
		failureReason = reason;
		return Status.FAILED;
	}
	
	@Override
	public String failureReason()
	{
		return failureReason;
	}
	
	private enum Phase
	{
		START,
		TRACK,
		STRUCK,
		CONFIRM;
	}
}
