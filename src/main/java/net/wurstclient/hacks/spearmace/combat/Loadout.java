/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.combat;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.enchantment.Enchantment;
import net.wurstclient.hacks.spearmace.state.Kit;

/**
 * Finds the combat items in the hotbar and reads the stats that matter for
 * attribute swapping straight from their components.
 */
public enum Loadout
{
	;
	
	/** Reach of items without an {@code ATTACK_RANGE} component. */
	public static final double DEFAULT_REACH = 3.0;
	
	/**
	 * The item held while the attack charges, before the swap to the mace.
	 */
	public enum PreItem
	{
		SPEAR,
		SWORD,
		AXE,
		HAND;
	}
	
	/** @return the hotbar slot of the mace with the best Wind Burst, or -1 */
	public static int mace()
	{
		return Kit.live().mace();
	}
	
	/** @return the hotbar slot of the spear with the most reach, or -1 */
	public static int spear()
	{
		return Kit.live().spear();
	}
	
	/** @return the hotbar slot of the strongest sword, or -1 */
	public static int sword()
	{
		return Kit.live().sword();
	}
	
	/** @return the hotbar slot of an item that disables shields, or -1 */
	public static int axe()
	{
		return Kit.live().axe();
	}
	
	public static int slotFor(PreItem pre)
	{
		return Kit.live().slotFor(pre);
	}
	
	/**
	 * Ticks for a full attack charge while holding {@code stack}, i.e.
	 * {@code Player.getCurrentItemAttackStrengthDelay()} once its attributes
	 * are applied.
	 */
	public static double attackDelay(ItemStack stack)
	{
		return Kit.live().attackDelay(stack);
	}
	
	/** The {@code ATTACK_DAMAGE} attribute while holding {@code stack}. */
	public static double attackDamage(ItemStack stack)
	{
		return Kit.live().attackDamage(stack);
	}
	
	/**
	 * The range the client's crosshair pick uses while holding
	 * {@code stack}.
	 */
	public static AttackRange reach(ItemStack stack)
	{
		AttackRange range = stack.get(DataComponents.ATTACK_RANGE);
		if(range != null)
			return range;
		
		return new AttackRange(0, (float)DEFAULT_REACH, 0, (float)DEFAULT_REACH,
			0, 1);
	}
	
	public static double disableBlockingSeconds(ItemStack stack)
	{
		Weapon weapon = stack.get(DataComponents.WEAPON);
		return weapon == null ? 0 : weapon.disableBlockingForSeconds();
	}
	
	public static int level(ItemStack stack, ResourceKey<Enchantment> key)
	{
		for(Holder<Enchantment> ench : stack.getEnchantments().keySet())
			if(ench.is(key))
				return stack.getEnchantments().getLevel(ench);
			
		return 0;
	}
}
