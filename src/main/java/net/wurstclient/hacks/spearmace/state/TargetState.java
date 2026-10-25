/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.state;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
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
 */
public record TargetState(int entityId, Vec3 pos, Vec3 vel, boolean onGround,
	boolean moves, double width, double height, double eyeHeight, float health,
	float absorption, int armor, boolean shieldRaised, float headYaw,
	int hurtTime)
{
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
			target.hurtTime);
	}
	
	/** Whether {@code target} is holding up a shield or something like it. */
	public static boolean shieldRaised(LivingEntity target)
	{
		return target.isUsingItem()
			&& target.getUseItem().has(DataComponents.BLOCKS_ATTACKS);
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
