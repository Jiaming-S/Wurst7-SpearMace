/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.state;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.sim.Move;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.state.Pending.PearlInFlight;
import net.wurstclient.hacks.spearmace.state.Pending.WindChargeInFlight;

/**
 * Everything the models need to know about the local player, detached
 * from the game so that it can describe a predicted state as well as the
 * current one.
 *
 * @param tick
 *            the player's {@code tickCount}
 * @param impactY
 *            the height of the server's {@code currentImpulseImpactPos},
 *            which caps the fall that counts for fall damage, or NaN if
 *            there is none. Only the server knows it, so it comes from
 *            the prediction.
 * @param impulseGrace
 *            the server's {@code currentImpulseContextResetGraceTime},
 *            ticks until standing on the ground clears {@code impactY}
 * @param move
 *            the movement keys held, see {@link PlayerSim}
 * @param jump
 *            whether the jump key is held
 * @param heldChanged
 *            whether the held item changed since the last player tick,
 *            which resets the attack charge in the next one
 * @param missTime
 *            {@code Minecraft.missTime}, which blocks attack clicks
 * @param interactionRange
 *            {@code Player.entityInteractionRange()}
 * @param knockbackResistance
 *            the {@code EXPLOSION_KNOCKBACK_RESISTANCE} attribute
 */
public record SelfState(int tick, Vec3 pos, Vec3 vel, boolean onGround,
	double fallDistance, double impactY, int impulseGrace, float yaw,
	float pitch, Move move, boolean sprinting, boolean jump, boolean fallFlying,
	int attackStrengthTicker, boolean heldChanged, int missTime,
	double interactionRange, double knockbackResistance, Kit kit,
	List<Pending> pending)
{
	private static final Minecraft MC = WurstClient.MC;
	
	public SelfState
	{
		pending = List.copyOf(pending);
	}
	
	public static SelfState capture()
	{
		return new SelfState(MC.player.tickCount, MC.player.position(),
			MC.player.getDeltaMovement(), MC.player.onGround(),
			MC.player.fallDistance, Double.NaN, 0, MC.player.getYRot(),
			MC.player.getXRot(), Move.ofHeldKeys(), MC.player.isSprinting(),
			MC.options.keyJump.isDown(), MC.player.isFallFlying(),
			MC.player.attackStrengthTicker,
			!ItemStack.isSameItem(MC.player.lastItemInMainHand,
				MC.player.getMainHandItem()),
			MC.missTime, MC.player.entityInteractionRange(),
			MC.player
				.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE),
			Kit.capture(), ownProjectiles());
	}
	
	private static List<Pending> ownProjectiles()
	{
		ArrayList<Pending> pending = new ArrayList<>();
		for(Entity e : MC.level.entitiesForRendering())
		{
			if(e.isRemoved())
				continue;
			
			if(e instanceof WindCharge c && c.getOwner() == MC.player)
				pending.add(new WindChargeInFlight(c.getId(), c.position(),
					c.getDeltaMovement(), c.tickCount,
					!c.getBoundingBox().expandTowards(c.getDeltaMovement())
						.inflate(1).intersects(MC.player.getBoundingBox())));
			else if(e instanceof ThrownEnderpearl p
				&& p.getOwner() == MC.player)
				pending.add(new PearlInFlight(p.getId(), p.position(),
					p.getDeltaMovement(), p.tickCount));
		}
		return pending;
	}
	
	/**
	 * Whether an attack click queued now with {@code weapon} would pass
	 * {@code Player.cannotAttackWithItem()} in the next tick, which checks
	 * the weapon's minimum charge against the attack speed of the item held
	 * now.
	 */
	public boolean canAttackNextTick(ItemStack weapon)
	{
		return ticksUntilAttack(weapon) == 0;
	}
	
	/**
	 * How many more updates to wait before queuing an attack click with
	 * {@code weapon}, if the held item doesn't change.
	 */
	public int ticksUntilAttack(ItemStack weapon)
	{
		float minCharge =
			weapon.getOrDefault(DataComponents.MINIMUM_ATTACK_CHARGE, 0F);
		double delay = kit.attackDelay(kit.held());
		for(int wait = 0; wait < 100; wait++)
		{
			// the next player tick resets a changed item's charge
			int ticker = heldChanged ? wait : attackStrengthTicker + wait + 1;
			if(ticker / delay >= minCharge)
				return wait;
		}
		return 100;
	}
	
	public Vec3 eyePos()
	{
		return pos.add(0, PlayerSim.EYE_HEIGHT, 0);
	}
	
	public AABB box()
	{
		return PlayerSim.box(pos);
	}
	
	/** This state as if the pearls in the air weren't there. */
	public SelfState withoutPearls()
	{
		ArrayList<Pending> rest = new ArrayList<>(pending);
		rest.removeIf(PearlInFlight.class::isInstance);
		return new SelfState(tick, pos, vel, onGround, fallDistance, impactY,
			impulseGrace, yaw, pitch, move, sprinting, jump, fallFlying,
			attackStrengthTicker, heldChanged, missTime, interactionRange,
			knockbackResistance, kit, rest);
	}
	
	public <T extends Pending> List<T> pending(Class<T> type)
	{
		return pending.stream().filter(type::isInstance).map(type::cast)
			.toList();
	}
}
