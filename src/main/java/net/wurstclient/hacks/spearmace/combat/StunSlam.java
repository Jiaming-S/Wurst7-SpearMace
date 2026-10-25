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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.input.KeyInput;
import net.wurstclient.hacks.spearmace.maneuver.Hotbar;
import net.wurstclient.hacks.spearmace.maneuver.Maneuver;
import net.wurstclient.hacks.spearmace.maneuver.Prediction;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.sim.StrikeModel;

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
public final class StunSlam implements Maneuver
{
	private static final Minecraft MC = WurstClient.MC;
	
	private static final int CONFIRM_TIMEOUT = MaceStrike.LAUNCH_DELAY + 3;
	private static final int MAX_TICKS = Engagement.HORIZON;
	
	private final LivingEntity target;
	
	private Phase phase = Phase.START;
	private String failureReason;
	private long startTick = -1;
	private long axeTick;
	private int axeSlot;
	private int maceSlot;
	private double expectedLaunch;
	private double measuredLaunch = Double.NaN;
	private double expectedDamage = Double.NaN;
	private double expectedFall = Double.NaN;
	private boolean shieldWasDown;
	
	public StunSlam(LivingEntity target)
	{
		this.target = target;
	}
	
	@Override
	public String name()
	{
		return "StunSlam";
	}
	
	@Override
	public Prediction predict()
	{
		String reason = checkPreconditions();
		if(reason != null)
			return Prediction.infeasible(reason);
		
		Engagement e = Engagement.now(target);
		ItemStack axe = Hotbar.get(Loadout.axe());
		int first = Hotbar.selected() == Loadout.axe() ? 0 : 1;
		int k = first;
		while(k + 2 < e.landing() && !canStart(e, axe, k))
			k++;
		if(k + 2 >= Math.min(e.landing(), Engagement.HORIZON))
			return Prediction.infeasible("no contact before landing");
		
		PlayerSim sim = PlayerSim.ofLocalPlayer();
		ArrayList<Vec3> path = new ArrayList<>();
		path.add(sim.pos);
		for(int i = 0; i < k + 1 + MaceStrike.LAUNCH_DELAY; i++)
		{
			sim.step();
			path.add(sim.pos);
		}
		
		sim.vel = new Vec3(sim.vel.x, MaceStrike.launchVelY(e.selfPos(k + 2)),
			sim.vel.z);
		sim.onGround = false;
		for(int i = 0; i < 100 && sim.vel.y > 0; i++)
		{
			sim.step();
			path.add(sim.pos);
		}
		return Prediction.of(path, sim.vel, 1);
	}
	
	/**
	 * Whether the axe click can be queued at update {@code k}: the axe hit
	 * lands at {@code k + 1}, the mace hit at {@code k + 2}.
	 */
	private static boolean canStart(Engagement e, ItemStack axe, int k)
	{
		return e.inReach(axe, k + 1) && e.inReach(axe, k + 2)
			&& StrikeModel.canSmash(e.fall(k + 2) - 0.1);
	}
	
	private String checkPreconditions()
	{
		if(Loadout.mace() < 0)
			return "no mace";
		if(Loadout.axe() < 0)
			return "no axe";
		if(!target.isAlive())
			return "target is dead";
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
			case AXED -> mace(tick);
			case MACED -> recover(tick);
			case CONFIRM -> confirm(tick);
		};
	}
	
	private Status start(long tick)
	{
		failureReason = checkPreconditions();
		if(failureReason != null)
			return Status.FAILED;
		
		axeSlot = Loadout.axe();
		maceSlot = Loadout.mace();
		if(Hotbar.selected() != axeSlot)
			KeyInput.selectSlot(axeSlot);
		
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
		if(e.landing() <= 2)
			return fail("landed before the hit");
		
		KeyInput.look(e.aim(1));
		if(Hotbar.selected() != axeSlot || MC.missTime > 0)
			return Status.RUNNING;
		
		if(!canStart(e, Hotbar.get(axeSlot), 0))
			return Status.RUNNING;
		
		KeyInput.clickAttack();
		axeTick = tick;
		phase = Phase.AXED;
		return Status.RUNNING;
	}
	
	private Status mace(long tick)
	{
		// a click that found nothing starts the miss cooldown
		if(MC.missTime > 0)
		{
			phase = Phase.TRACK;
			return Status.RUNNING;
		}
		
		Engagement e = Engagement.now(target);
		KeyInput.look(e.aim(1));
		KeyInput.selectSlot(maceSlot);
		KeyInput.clickAttack();
		
		// the axe hit reset the charge, so this one is weak but still smashes
		ItemStack axe = Hotbar.get(axeSlot);
		double strength = StrikeModel.strength(1, Loadout.attackDelay(axe));
		expectedFall = e.fall(1);
		expectedDamage = StrikeModel.damage(Loadout.attackDamage(axe), strength,
			expectedFall,
			Loadout.level(Hotbar.get(maceSlot), Enchantments.DENSITY), false);
		expectedLaunch = MaceStrike.launchVelY(e.selfPos(1));
		phase = Phase.MACED;
		return Status.RUNNING;
	}
	
	private Status recover(long tick)
	{
		if(Hotbar.selected() != maceSlot)
			return fail("hotbar didn't switch to the mace");
		
		shieldWasDown = !Engagement.shieldRaised(target);
		KeyInput.selectSlot(axeSlot);
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
		
		if(tick - axeTick > CONFIRM_TIMEOUT + 1)
			return fail("no Wind Burst launch");
		
		return Status.RUNNING;
	}
	
	public double expectedDamage()
	{
		return expectedDamage;
	}
	
	public double expectedFall()
	{
		return expectedFall;
	}
	
	public double expectedLaunchVelY()
	{
		return expectedLaunch;
	}
	
	public double measuredLaunchVelY()
	{
		return measuredLaunch;
	}
	
	/** Whether the client saw the shield lowered right after the axe hit. */
	public boolean shieldWasDown()
	{
		return shieldWasDown;
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
		AXED,
		MACED,
		CONFIRM;
	}
}
