/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.combat;

import java.util.function.ToDoubleFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.maneuver.Hotbar;

/**
 * Finds the combat items in the hotbar and reads the stats that matter for
 * attribute swapping straight from their components.
 */
public enum Loadout
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	
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
		return best(
			s -> s.is(Items.MACE) ? 1 + level(s, Enchantments.WIND_BURST) : 0);
	}
	
	/** @return the hotbar slot of the spear with the most reach, or -1 */
	public static int spear()
	{
		return best(s -> s.has(DataComponents.PIERCING_WEAPON)
			? reach(s).maxReach() : 0);
	}
	
	/** @return the hotbar slot of the strongest sword, or -1 */
	public static int sword()
	{
		return best(s -> s.is(ItemTags.SWORDS) ? attackDamage(s) : 0);
	}
	
	/** @return the hotbar slot of an item that disables shields, or -1 */
	public static int axe()
	{
		return best(Loadout::disableBlockingSeconds);
	}
	
	public static int slotFor(PreItem pre)
	{
		return switch(pre)
		{
			case SPEAR -> spear();
			case SWORD -> sword();
			case AXE -> axe();
			case HAND -> Hotbar.hand();
		};
	}
	
	/**
	 * Ticks for a full attack charge while holding {@code stack}, i.e.
	 * {@code Player.getCurrentItemAttackStrengthDelay()} once its attributes
	 * are applied.
	 */
	public static double attackDelay(ItemStack stack)
	{
		return 20 / attribute(stack, Attributes.ATTACK_SPEED);
	}
	
	/** The {@code ATTACK_DAMAGE} attribute while holding {@code stack}. */
	public static double attackDamage(ItemStack stack)
	{
		return attribute(stack, Attributes.ATTACK_DAMAGE);
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
	
	/**
	 * The player's base value plus the stack's flat main hand modifiers,
	 * ignoring effects and other equipment.
	 */
	private static double attribute(ItemStack stack,
		Holder<Attribute> attribute)
	{
		double[] value = {MC.player.getAttributeBaseValue(attribute)};
		stack.forEachModifier(EquipmentSlot.MAINHAND, (attr, modifier) -> {
			if(attr.is(attribute) && modifier
				.operation() == AttributeModifier.Operation.ADD_VALUE)
				value[0] += modifier.amount();
		});
		return value[0];
	}
	
	private static int best(ToDoubleFunction<ItemStack> score)
	{
		int best = -1;
		double bestScore = 0;
		for(int i = 0; i < Inventory.getSelectionSize(); i++)
		{
			double s = score.applyAsDouble(Hotbar.get(i));
			if(s > bestScore)
			{
				best = i;
				bestScore = s;
			}
		}
		return best;
	}
}
