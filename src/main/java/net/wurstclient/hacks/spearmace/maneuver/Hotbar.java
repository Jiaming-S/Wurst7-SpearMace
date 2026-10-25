/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.maneuver;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.wurstclient.WurstClient;

/** Finds the hotbar slots that maneuvers need. */
public enum Hotbar
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	
	public static int selected()
	{
		return MC.player.getInventory().getSelectedSlot();
	}
	
	public static ItemStack get(int slot)
	{
		return MC.player.getInventory().getItem(slot);
	}
	
	/** @return a hotbar slot holding wind charges that are ready, or -1 */
	public static int windCharge()
	{
		return readySlot(s -> s.is(Items.WIND_CHARGE));
	}
	
	/** @return a hotbar slot holding ender pearls that are ready, or -1 */
	public static int enderPearl()
	{
		return readySlot(s -> s.is(Items.ENDER_PEARL));
	}
	
	/** @return the hotbar slot of the spear with the best Lunge, or -1 */
	public static int lungeSpear()
	{
		int best = -1;
		int bestLevel = 0;
		for(int i = 0; i < Inventory.getSelectionSize(); i++)
		{
			int level = lungeLevel(get(i));
			if(level > bestLevel)
			{
				best = i;
				bestLevel = level;
			}
		}
		return best;
	}
	
	public static int lungeLevel(ItemStack stack)
	{
		if(!stack.has(DataComponents.PIERCING_WEAPON))
			return 0;
		
		for(Holder<Enchantment> ench : stack.getEnchantments().keySet())
			if(ench.is(Enchantments.LUNGE))
				return stack.getEnchantments().getLevel(ench);
			
		return 0;
	}
	
	/**
	 * A slot to hold before the spear swap. Its item must not change the
	 * attack speed, because the client checks the attack charge against the
	 * held item's attack speed. Prefers the selected slot, then wind charges,
	 * then empty slots, then anything else without attack speed.
	 *
	 * @return the slot, or -1 if every slot holds a weapon or tool
	 */
	public static int hand()
	{
		if(isHand(get(selected())))
			return selected();
		
		int any = -1;
		int empty = -1;
		for(int i = 0; i < Inventory.getSelectionSize(); i++)
		{
			ItemStack stack = get(i);
			if(!isHand(stack))
				continue;
			if(stack.is(Items.WIND_CHARGE))
				return i;
			if(stack.isEmpty() && empty < 0)
				empty = i;
			if(any < 0)
				any = i;
		}
		return empty >= 0 ? empty : any;
	}
	
	public static boolean isHand(ItemStack stack)
	{
		AtomicBoolean changesSpeed = new AtomicBoolean();
		stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
			if(attribute.is(Attributes.ATTACK_SPEED))
				changesSpeed.set(true);
		});
		return !changesSpeed.get();
	}
	
	private static int readySlot(Predicate<ItemStack> item)
	{
		for(int i = 0; i < Inventory.getSelectionSize(); i++)
		{
			ItemStack stack = get(i);
			if(item.test(stack)
				&& !MC.player.getCooldowns().isOnCooldown(stack))
				return i;
		}
		return -1;
	}
}
