/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.maneuver;

import java.util.ArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.input.KeyInput;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
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
public final class SpearLunge implements Maneuver
{
	private static final Minecraft MC = WurstClient.MC;
	
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
	
	private final float yaw;
	private final float pitch;
	
	private Phase phase = Phase.START;
	private String failureReason;
	private int spearSlot;
	private int handSlot;
	private long waitStart;
	private long stabTick;
	private int attempts;
	private Vec3 impulse;
	private Vec3 measured;
	private PlayerSim shadow;
	
	public SpearLunge(float yaw, float pitch)
	{
		this.yaw = yaw;
		this.pitch = pitch;
	}
	
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
	public Prediction predict()
	{
		String reason = checkPreconditions();
		if(reason != null)
			return Prediction.infeasible(reason);
		
		int level = Hotbar.lungeLevel(Hotbar.get(Hotbar.lungeSpear()));
		int wait = ticksUntilStab();
		if(wait > MAX_WAIT)
			return Prediction.infeasible("attack charge takes too long");
		
		PlayerSim sim = PlayerSim.ofLocalPlayer();
		ArrayList<Vec3> path = new ArrayList<>();
		path.add(sim.pos);
		for(int i = 0; i < wait; i++)
		{
			sim.step();
			path.add(sim.pos);
		}
		
		sim.yaw = yaw;
		for(int i = 0; i < IMPULSE_DELAY; i++)
		{
			sim.step();
			path.add(sim.pos);
		}
		sim.vel = sim.vel.add(impulse(yaw, pitch, level));
		return Prediction.of(path, sim.vel, 1);
	}
	
	/** Updates until the attack click can be queued. */
	private int ticksUntilStab()
	{
		ItemStack spear = Hotbar.get(Hotbar.lungeSpear());
		if(Hotbar.selected() == Hotbar.hand())
			return KeyInput.ticksUntilAttack(spear);
			
		// the switch resets the attack charge one tick later, then it has to
		// fill up again for the hand item (5 ticks for attack speed 4)
		float delay = 1 / 4F * 20;
		return 1 + (int)Math.ceil(delay);
	}
	
	private String checkPreconditions()
	{
		LocalPlayer player = MC.player;
		if(Hotbar.lungeSpear() < 0)
			return "no spear with Lunge";
		if(Hotbar.hand() < 0)
			return "no hotbar slot without a weapon or tool";
		if(player.isPassenger())
			return "riding";
		if(player.isFallFlying())
			return "gliding";
		if(player.isInWater())
			return "in water";
		if(!player.isCreative() && player.getFoodData().getFoodLevel() < 7)
			return "too hungry";
		return null;
	}
	
	@Override
	public Status tick(long tick)
	{
		return switch(phase)
		{
			case START -> start(tick);
			case WAIT -> waitForCharge(tick);
			case STABBED -> swapBack(tick);
			case CONFIRM -> confirm(tick);
		};
	}
	
	private Status start(long tick)
	{
		failureReason = checkPreconditions();
		if(failureReason != null)
			return Status.FAILED;
		
		spearSlot = Hotbar.lungeSpear();
		handSlot = Hotbar.hand();
		waitStart = tick;
		phase = Phase.WAIT;
		
		if(Hotbar.selected() != handSlot)
		{
			KeyInput.selectSlot(handSlot);
			return Status.RUNNING;
		}
		
		return waitForCharge(tick);
	}
	
	private Status waitForCharge(long tick)
	{
		if(tick - waitStart > MAX_WAIT)
			return fail("attack charge never filled up");
		
		ItemStack spear = Hotbar.get(spearSlot);
		if(Hotbar.selected() != handSlot || !KeyInput.canAttackNextTick(spear))
			return Status.RUNNING;
		
		failureReason = checkPreconditions();
		if(failureReason != null)
			return Status.FAILED;
		
		KeyInput.look(new Rotation(yaw, pitch));
		KeyInput.selectSlot(spearSlot);
		KeyInput.clickAttack();
		
		stabTick = tick;
		attempts++;
		impulse = impulse(yaw, pitch, Hotbar.lungeLevel(spear));
		shadow = PlayerSim.ofLocalPlayer();
		phase = Phase.STABBED;
		return Status.RUNNING;
	}
	
	private Status swapBack(long tick)
	{
		// handleKeybinds() has switched to the spear and stabbed by now
		if(Hotbar.selected() != spearSlot)
			return fail("hotbar didn't switch to the spear");
		
		KeyInput.selectSlot(handSlot);
		phase = Phase.CONFIRM;
		return confirm(tick);
	}
	
	private Status confirm(long tick)
	{
		// compare with where the player would be without the lunge
		shadow.step();
		Vec3 diff = MC.player.getDeltaMovement().subtract(shadow.vel);
		shadow = PlayerSim.ofLocalPlayer();
		
		Vec3 dir = impulse.normalize();
		if(diff.dot(dir) >= 0.6 * impulse.length())
		{
			measured = new Vec3(diff.x, 0, diff.z);
			return Status.DONE;
		}
		
		if(tick - stabTick > CONFIRM_TIMEOUT)
		{
			if(attempts >= MAX_ATTEMPTS)
				return fail("no lunge impulse after " + attempts + " attempts");
			
			waitStart = tick;
			phase = Phase.WAIT;
		}
		
		return Status.RUNNING;
	}
	
	/** The horizontal push that actually happened, or {@code null}. */
	public Vec3 measuredImpulse()
	{
		return measured;
	}
	
	public Vec3 expectedImpulse()
	{
		return impulse;
	}
	
	public int attempts()
	{
		return attempts;
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
		WAIT,
		STABBED,
		CONFIRM;
	}
}
