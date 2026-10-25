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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.input.KeyInput;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.sim.Shots;
import net.wurstclient.hacks.spearmace.sim.WindBurst;
import net.wurstclient.hacks.spearmace.sim.WindChargeSim;
import net.wurstclient.util.Rotation;

/**
 * Throws a wind charge at the ground (straight down by default) to launch
 * the player upwards. Works standing or while moving, and can jump in the
 * same tick for extra height. Done at the top of the launch.
 */
public final class WindChargeJump implements Maneuver
{
	private static final Minecraft MC = WurstClient.MC;
	
	/** How long a charge may fly before the launch counts as failed. */
	private static final int MAX_FLIGHT = 12;
	
	private final Float yaw;
	private final float pitch;
	private final boolean jumpFirst;
	
	private Phase phase = Phase.THROW;
	private String failureReason;
	private int prevSlot;
	private int chargeSlot;
	private long throwTick;
	private boolean holdingJump;
	private PlayerSim shadow;
	private Vec3 burst;
	
	/**
	 * @param yaw
	 *            {@code null} to keep the current yaw
	 * @param pitch
	 *            90 throws straight down
	 */
	public WindChargeJump(Float yaw, float pitch, boolean jumpFirst)
	{
		this.yaw = yaw;
		this.pitch = pitch;
		this.jumpFirst = jumpFirst;
	}
	
	/** Straight down, keeping the current yaw. */
	public static WindChargeJump atFeet(boolean jumpFirst)
	{
		return new WindChargeJump(null, 90, jumpFirst);
	}
	
	@Override
	public String name()
	{
		return String.format("WindChargeJump(pitch=%.1f, jump=%s)", pitch,
			jumpFirst);
	}
	
	private Rotation aim()
	{
		return new Rotation(yaw == null ? MC.player.getYRot() : yaw, pitch);
	}
	
	@Override
	public Prediction predict()
	{
		if(Hotbar.windCharge() < 0)
			return Prediction.infeasible("no wind charge ready");
		
		PlayerSim sim = PlayerSim.ofLocalPlayer();
		sim.yaw = aim().yaw();
		sim.jump = jumpFirst && sim.onGround;
		return simulate(MC.level, sim, aim());
	}
	
	/**
	 * Predicts a wind charge clicked at the current update.
	 *
	 * @param sim
	 *            the player at the current update (will be modified)
	 */
	public static Prediction simulate(Level level, PlayerSim sim, Rotation aim)
	{
		ArrayList<Vec3> path = new ArrayList<>();
		path.add(sim.pos);
		
		// the server spawns the charge at the position after this tick
		Vec3 before = sim.pos;
		sim.step();
		sim.jump = false;
		path.add(sim.pos);
		WindChargeSim charge =
			new WindChargeSim(WindChargeSim.spawnPos(sim.pos),
				Shots.velocity(Shots.direction(aim.yaw(), aim.pitch()),
					Vec3.ZERO, sim.pos.subtract(before), sim.onGround));
		
		// the server computes the knockback from the position after client
		// tick m, but it only reaches the player's velocity after tick m+1
		Vec3 pending = null;
		boolean launched = false;
		for(int tick = 0; tick < 200; tick++)
		{
			// client tick, then the server tick that handles its packets
			sim.step();
			path.add(sim.pos);
			
			if(pending != null)
			{
				sim.vel = sim.vel.add(pending);
				pending = null;
				launched = true;
				continue;
			}
			
			if(launched)
			{
				if(sim.vel.y <= 0)
					return Prediction.of(path, sim.vel, 1);
				continue;
			}
			
			if(tick >= MAX_FLIGHT)
				return Prediction.infeasible("the charge hits nothing");
			
			Vec3 center = charge.step(level, null);
			if(center == null)
				continue;
			
			pending = WindBurst.knockback(level, center, sim.pos);
			if(pending.y <= 0)
				return Prediction.infeasible("the explosion doesn't lift");
		}
		
		return Prediction.of(path, sim.vel, 1);
	}
	
	@Override
	public Status tick(long tick)
	{
		return switch(phase)
		{
			case THROW -> throwCharge(tick);
			case WAIT_BURST -> waitForBurst(tick);
			case RISING -> rise();
		};
	}
	
	private Status throwCharge(long tick)
	{
		chargeSlot = Hotbar.windCharge();
		if(chargeSlot < 0)
			return fail("no wind charge ready");
		
		prevSlot = Hotbar.selected();
		KeyInput.look(aim());
		if(jumpFirst && MC.player.onGround())
		{
			KeyInput.holdJump(true);
			holdingJump = true;
		}
		KeyInput.selectSlot(chargeSlot);
		KeyInput.clickUse();
		
		throwTick = tick;
		shadow = PlayerSim.ofLocalPlayer();
		phase = Phase.WAIT_BURST;
		return Status.RUNNING;
	}
	
	private Status waitForBurst(long tick)
	{
		if(holdingJump)
		{
			KeyInput.holdJump(false);
			holdingJump = false;
		}
		if(tick == throwTick + 1 && prevSlot != chargeSlot)
			KeyInput.selectSlot(prevSlot);
		
		// compare with where the player would be without the explosion
		shadow.step();
		Vec3 diff = MC.player.getDeltaMovement().subtract(shadow.vel);
		shadow = PlayerSim.ofLocalPlayer();
		if(diff.y > 0.2)
		{
			burst = diff;
			phase = Phase.RISING;
			return rise();
		}
		
		if(tick - throwTick > MAX_FLIGHT + 2)
			return fail("no explosion");
		
		return Status.RUNNING;
	}
	
	private Status rise()
	{
		// still on the ground right after the burst, so only check velocity
		if(MC.player.getDeltaMovement().y <= 0)
			return Status.DONE;
		
		return Status.RUNNING;
	}
	
	/** The velocity the explosion added, or {@code null} before it. */
	public Vec3 burst()
	{
		return burst;
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
		THROW,
		WAIT_BURST,
		RISING;
	}
}
