/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.state;

import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What the models need to know about the target, as the client sees it.
 * The client shows other entities a few ticks late, so the velocity comes
 * from the last two client positions rather than from the server.
 *
 * @param moves
 *            whether the target moves at all (NoAI mobs don't even fall)
 * @param shieldRaised
 *            whether it holds up a shield or something like it
 * @param headYaw
 *            where a raised shield faces
 * @param hurtTime
 *            ticks of hurt invulnerability left, as far as the server is
 *            concerned
 * @param shieldDisabled
 *            ticks until a knocked down shield works again; the client
 *            can't see this, so captures start at 0
 * @param player
 *            whether it is a player, which can hit back
 */
public record TargetState(int entityId, Vec3 pos, Vec3 vel, boolean onGround,
	boolean moves, double width, double height, double eyeHeight, float health,
	float absorption, int armor, boolean shieldRaised, float headYaw,
	int hurtTime, int shieldDisabled, boolean player)
{
	/** {@code LivingEntity.hurtTime} after a hit. */
	public static final int HURT_TIME = 10;
	
	public static TargetState capture(LivingEntity target)
	{
		boolean moves = !(target instanceof Mob mob && mob.isNoAi())
			&& !target.isNoGravity();
		Vec3 vel = target.position().subtract(target.oldPosition());
		return new TargetState(target.getId(), target.position(),
			moves ? vel : Vec3.ZERO, target.onGround(), moves,
			target.getBbWidth(), target.getBbHeight(), target.getEyeHeight(),
			target.getHealth(), target.getAbsorptionAmount(),
			target.getArmorValue(), shieldRaised(target), target.getYHeadRot(),
			target.hurtTime, 0, target instanceof Player);
	}
	
	/** Whether {@code target} is holding up a shield or something like it. */
	public static boolean shieldRaised(LivingEntity target)
	{
		return target.isUsingItem()
			&& target.getUseItem().has(DataComponents.BLOCKS_ATTACKS);
	}
	
	/**
	 * Whether a raised shield would block a hit from a player at
	 * {@code attackerFeet}, as in {@code LivingEntity.applyItemBlocking()}.
	 * Being straight above the target counts as in front of it.
	 */
	public boolean blocks(Vec3 attackerFeet)
	{
		if(!shieldRaised || shieldDisabled > 0)
			return false;
		
		float yaw = headYaw * Mth.DEG_TO_RAD;
		Vec3 view = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
		Vec3 toUs = attackerFeet.subtract(pos);
		toUs = new Vec3(toUs.x, 0, toUs.z).normalize();
		return Math.acos(toUs.dot(view)) <= Math.PI / 2 + 1e-6;
	}
	
	/** Whether a hit now would be ignored, as during hurt invulnerability. */
	public boolean invulnerable()
	{
		return hurtTime > 0;
	}
	
	public TargetState withPos(Vec3 newPos)
	{
		return new TargetState(entityId, newPos, vel, onGround, moves, width,
			height, eyeHeight, health, absorption, armor, shieldRaised, headYaw,
			hurtTime, shieldDisabled, player);
	}
	
	/** The same target after moving, with its timers ticked. */
	public TargetState withMove(Vec3 newPos, Vec3 newVel, boolean newOnGround)
	{
		return new TargetState(entityId, newPos, newVel, newOnGround, moves,
			width, height, eyeHeight, health, absorption, armor, shieldRaised,
			headYaw, Math.max(0, hurtTime - 1), Math.max(0, shieldDisabled - 1),
			player);
	}
	
	/** The same target right after a hit, before its health changes. */
	public TargetState withHurt()
	{
		return new TargetState(entityId, pos, vel, onGround, moves, width,
			height, eyeHeight, health, absorption, armor, shieldRaised, headYaw,
			HURT_TIME, shieldDisabled, player);
	}
	
	/** The same target after losing {@code damage}, ignoring armor. */
	public TargetState withHealthLost(double damage)
	{
		double fromAbsorption = Math.min(absorption, damage);
		return new TargetState(entityId, pos, vel, onGround, moves, width,
			height, eyeHeight, (float)(health - (damage - fromAbsorption)),
			(float)(absorption - fromAbsorption), armor, shieldRaised, headYaw,
			hurtTime, shieldDisabled, player);
	}
	
	/** The same target with its shield knocked down for {@code ticks}. */
	public TargetState withShieldDisabled(int ticks)
	{
		return new TargetState(entityId, pos, vel, onGround, moves, width,
			height, eyeHeight, health, absorption, armor, shieldRaised, headYaw,
			hurtTime, ticks, player);
	}
	
	/**
	 * This captured target, keeping what only the model knows from
	 * {@code predicted}: the server's hurt and shield timers.
	 */
	public TargetState withTimersFrom(TargetState predicted)
	{
		if(predicted == null || predicted.entityId != entityId)
			return this;
		
		return new TargetState(entityId, pos, vel, onGround, moves, width,
			height, eyeHeight, health, absorption, armor, shieldRaised, headYaw,
			Math.max(hurtTime, predicted.hurtTime), predicted.shieldDisabled,
			player);
	}
	
	public AABB box()
	{
		return box(pos);
	}
	
	public AABB box(Vec3 feet)
	{
		double halfWidth = width / 2;
		return new AABB(feet.x - halfWidth, feet.y, feet.z - halfWidth,
			feet.x + halfWidth, feet.y + height, feet.z + halfWidth);
	}
	
	public Vec3 eyePos()
	{
		return pos.add(0, eyeHeight, 0);
	}
}
