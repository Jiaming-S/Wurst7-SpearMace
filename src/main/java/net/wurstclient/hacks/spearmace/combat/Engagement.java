/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.sim.TargetSim;
import net.wurstclient.util.Rotation;

/**
 * Where the player and a target will be over the next {@link #HORIZON}
 * ticks if nobody does anything, and whether an attack would connect.
 *
 * <p>
 * Index {@code i} is the state after {@code i} ticks. An attack click
 * queued in the update at index {@code k} is handled by the next tick's
 * {@code handleKeybinds()}, which attacks whatever that tick's crosshair
 * pick found. That pick sees the state at index {@code k + 1}.
 */
public final class Engagement
{
	private static final Minecraft MC = WurstClient.MC;
	
	public static final int HORIZON = 60;
	
	/** Kept off every reach limit, for prediction error. */
	public static final double REACH_SAFETY = 0.25;
	
	/**
	 * {@code ServerGamePacketListenerImpl.handleAttack()} allows the held
	 * item's reach plus this much. The mace has the default reach, so the
	 * server accepts swapped hits from up to 6 blocks.
	 */
	public static final double SERVER_BUFFER = 3.0;
	
	private final LivingEntity target;
	private final Vec3[] selfPos = new Vec3[HORIZON + 1];
	private final Vec3[] selfVel = new Vec3[HORIZON + 1];
	private final double[] fall = new double[HORIZON + 1];
	private final AABB[] targetBox = new AABB[HORIZON + 1];
	private final Vec3[] targetPos = new Vec3[HORIZON + 1];
	private int landing = HORIZON + 1;
	
	private Engagement(LivingEntity target)
	{
		this.target = target;
		
		PlayerSim self = PlayerSim.ofLocalPlayer();
		TargetSim other = TargetSim.of(target);
		double fallDistance = MC.player.fallDistance;
		if(MC.player.onGround())
			landing = 0;
		
		for(int i = 0; i <= HORIZON; i++)
		{
			if(i > 0)
			{
				Vec3 before = self.pos;
				self.step();
				other.step();
				
				// Entity.checkFallDamage()
				if(self.pos.y < before.y)
					fallDistance += before.y - self.pos.y;
				if(self.onGround)
				{
					fallDistance = 0;
					landing = Math.min(landing, i);
				}
			}
			
			selfPos[i] = self.pos;
			selfVel[i] = self.vel;
			fall[i] = fallDistance;
			targetPos[i] = other.pos;
			targetBox[i] = other.box();
		}
	}
	
	public static Engagement now(LivingEntity target)
	{
		return new Engagement(target);
	}
	
	public LivingEntity target()
	{
		return target;
	}
	
	/** The first index at which the player stands on the ground. */
	public int landing()
	{
		return landing;
	}
	
	public Vec3 selfPos(int i)
	{
		return selfPos[i];
	}
	
	public Vec3 selfVel(int i)
	{
		return selfVel[i];
	}
	
	public Vec3 eye(int i)
	{
		return selfPos[i].add(0, PlayerSim.EYE_HEIGHT, 0);
	}
	
	/** The player's fall distance after {@code i} ticks. */
	public double fall(int i)
	{
		return fall[i];
	}
	
	public AABB targetBox(int i)
	{
		return targetBox[i];
	}
	
	public Vec3 targetPos(int i)
	{
		return targetPos[i];
	}
	
	/** The view that points at the middle of the target at index i. */
	public Rotation aim(int i)
	{
		return aim(eye(i), targetBox[i].getCenter());
	}
	
	/** Same math as {@code RotationUtils.getNeededRotations()}. */
	public static Rotation aim(Vec3 eye, Vec3 point)
	{
		double diffX = point.x - eye.x;
		double diffZ = point.z - eye.z;
		double yaw = Math.toDegrees(Math.atan2(diffZ, diffX)) - 90F;
		double diffY = point.y - eye.y;
		double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
		double pitch = -Math.toDegrees(Math.atan2(diffY, diffXZ));
		return Rotation.wrapped((float)yaw, (float)pitch);
	}
	
	/** Distance from the eyes to the closest point of the target. */
	public double distance(int i)
	{
		return Math.sqrt(targetBox[i].distanceToSqr(eye(i)));
	}
	
	/**
	 * Whether the crosshair pick at index {@code i}, while holding
	 * {@code held} and looking at {@link #aim(int)}, would find the target,
	 * and whether the server would then accept a mace hit.
	 */
	public boolean inReach(ItemStack held, int i)
	{
		if(i >= landing || i > HORIZON)
			return false;
		
		AABB box = targetBox[i];
		Vec3 eye = eye(i);
		if(distance(i) > Loadout.DEFAULT_REACH + SERVER_BUFFER - REACH_SAFETY)
			return false;
		
		Vec3 look = aim(i).toLookVec();
		
		// LocalPlayer.raycastHitResult(): items with an attack range pick
		// along a segment, which is longer when moving along the view
		AttackRange range = held.get(DataComponents.ATTACK_RANGE);
		if(range != null)
		{
			double extra = Math.max(0, selfVel[i].dot(look));
			Vec3 from = eye.add(look.scale(range.minReach()));
			Vec3 to =
				eye.add(look.scale(range.maxReach() + extra - REACH_SAFETY));
			AABB inflated = box.inflate(range.hitboxMargin());
			if(inflated.contains(from) || inflated.clip(from, to).isPresent())
				return true;
		}
		
		// otherwise it falls back to the normal pick
		double reach = MC.player.entityInteractionRange() - REACH_SAFETY;
		return box.contains(eye)
			|| box.clip(eye, eye.add(look.scale(reach))).isPresent();
	}
	
	/**
	 * Lists the distance and fall distance of the first few ticks, marking
	 * those where {@code held} would reach. For failure messages.
	 */
	public String describe(ItemStack held, int ticks)
	{
		StringBuilder sb = new StringBuilder("landing=" + landing);
		for(int i = 1; i <= Math.min(ticks, HORIZON); i++)
			sb.append(String.format(" %d:%.2f/%.2f%s", i, distance(i), fall[i],
				inReach(held, i) ? "*" : ""));
		return sb.toString();
	}
	
	/** Whether the target is holding up a shield or something like it. */
	public static boolean shieldRaised(LivingEntity target)
	{
		return target.isUsingItem()
			&& target.getUseItem().has(DataComponents.BLOCKS_ATTACKS);
	}
	
	/**
	 * Whether a raised shield would block a hit from the player at index
	 * {@code i}, as in {@code LivingEntity.applyItemBlocking()}. Being
	 * straight above the target counts as in front of it.
	 */
	public boolean shieldFacingUs(int i)
	{
		if(!shieldRaised(target))
			return false;
		
		float yaw = target.getYHeadRot() * Mth.DEG_TO_RAD;
		Vec3 view = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
		Vec3 toUs = selfPos[i].subtract(targetPos[i]);
		toUs = new Vec3(toUs.x, 0, toUs.z).normalize();
		return Math.acos(toUs.dot(view)) <= Math.PI / 2 + 1e-6;
	}
}
