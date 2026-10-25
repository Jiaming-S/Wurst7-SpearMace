/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.sim;

import net.minecraft.util.Mth;

/**
 * Damage and Wind Burst formulas for an attribute-swapped mace hit, copied
 * from {@code Player.attack()}, {@code MaceItem} and {@code wind_burst.json}.
 *
 * <p>
 * In an attribute swap, {@code ATTACK_DAMAGE} and the attack charge still
 * come from the item held before the swap, but the mace's damage bonus and
 * enchantments apply. The bonus is added <i>before</i> the critical hit
 * multiplier, so a crit multiplies the whole smash.
 */
public enum StrikeModel
{
	;
	
	/** {@code fullStrengthAttack} needs a charge above this. */
	public static final double FULL_STRENGTH = 0.9;
	public static final double CRIT_MULTIPLIER = 1.5;
	
	/** {@code MaceItem.SMASH_ATTACK_FALL_THRESHOLD} */
	public static final double SMASH_FALL = 1.5;
	
	/** {@code MaceItem.hurtEnemy()} sets the attacker's y velocity to this. */
	public static final double SMASH_VEL_Y = 0.01F;
	
	/** {@code getAttackStrengthScale(0.5F)} at the given ticker. */
	public static double strength(double ticker, double delay)
	{
		return Mth.clamp((ticker + 0.5) / delay, 0, 1);
	}
	
	public static boolean isFull(double strength)
	{
		return strength > FULL_STRENGTH;
	}
	
	public static boolean canSmash(double fallDistance)
	{
		return fallDistance > SMASH_FALL;
	}
	
	/** {@code MaceItem.getAttackDamageBonus()}, including Density. */
	public static double maceBonus(double fall, int density)
	{
		if(!canSmash(fall))
			return 0;
		
		double damage;
		if(fall <= 3)
			damage = 4 * fall;
		else if(fall <= 8)
			damage = 12 + 2 * (fall - 3);
		else
			damage = 22 + fall - 8;
		
		// smash_damage_per_fallen_block: 0.5 per level
		return damage + 0.5 * density * fall;
	}
	
	/**
	 * Damage before armor of a mace hit swapped from an item with
	 * {@code attackDamage}, ignoring the magic boost of other enchantments.
	 */
	public static double damage(double attackDamage, double strength,
		double fall, int density, boolean crit)
	{
		double damage = attackDamage * (0.2 + strength * strength * 0.8);
		damage += maceBonus(fall, density);
		if(crit)
			damage *= CRIT_MULTIPLIER;
		return damage;
	}
	
	/** Wind Burst's {@code knockback_multiplier} lookup. */
	public static double windBurstMultiplier(int level)
	{
		return switch(level)
		{
			case 0 -> 0;
			case 1 -> 1.2;
			case 2 -> 1.75;
			case 3 -> 2.2;
			default -> 1.5 + 0.35 * (level - 1);
		};
	}
	
	/**
	 * The attacker's y velocity after a smash with Wind Burst. The explosion
	 * is centered on the attacker's feet, so it pushes straight up at full
	 * power, on top of the {@link #SMASH_VEL_Y} the mace sets first.
	 *
	 * @param exposure
	 *            {@link WindBurst#getSeenPercent}, 1 in open air
	 * @param resistance
	 *            the {@code EXPLOSION_KNOCKBACK_RESISTANCE} attribute
	 */
	public static double windBurstVelY(int level, double exposure,
		double resistance)
	{
		return SMASH_VEL_Y
			+ windBurstMultiplier(level) * exposure * (1 - resistance);
	}
}
