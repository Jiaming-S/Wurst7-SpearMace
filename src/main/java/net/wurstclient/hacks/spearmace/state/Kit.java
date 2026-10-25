/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.combat.Loadout;
import net.wurstclient.hacks.spearmace.combat.Loadout.PreItem;
import net.wurstclient.hacks.spearmace.maneuver.Hotbar;

/**
 * The hotbar and everything about it that the models read: the items, the
 * selected slot, item cooldowns and the player's base attack attributes.
 *
 * <p>
 * {@link #capture()} copies the items, so the snapshot stays valid after
 * the inventory changes. {@link #live()} shares them and is only meant for
 * a single query.
 */
public final class Kit
{
	private static final Minecraft MC = WurstClient.MC;
	
	private final List<ItemStack> slots;
	private final int selected;
	
	/** Remaining ticks per cooldown group, only for active cooldowns. */
	private final Map<Identifier, Integer> cooldowns;
	
	private final double baseAttackSpeed;
	private final double baseAttackDamage;
	
	public Kit(List<ItemStack> slots, int selected,
		Map<Identifier, Integer> cooldowns, double baseAttackSpeed,
		double baseAttackDamage)
	{
		this.slots = List.copyOf(slots);
		this.selected = selected;
		this.cooldowns = Map.copyOf(cooldowns);
		this.baseAttackSpeed = baseAttackSpeed;
		this.baseAttackDamage = baseAttackDamage;
	}
	
	/** A snapshot of the local player's hotbar. */
	public static Kit capture()
	{
		return of(true);
	}
	
	/** The local player's hotbar, sharing its item stacks. */
	public static Kit live()
	{
		return of(false);
	}
	
	private static Kit of(boolean copy)
	{
		Inventory inventory = MC.player.getInventory();
		ArrayList<ItemStack> slots = new ArrayList<>();
		for(int i = 0; i < Inventory.getSelectionSize(); i++)
		{
			ItemStack stack = inventory.getItem(i);
			slots.add(copy ? stack.copy() : stack);
		}
		
		ItemCooldowns live = MC.player.getCooldowns();
		HashMap<Identifier, Integer> cooldowns = new HashMap<>();
		live.cooldowns.forEach((group, instance) -> {
			int remaining = instance.endTime() - live.tickCount;
			if(remaining > 0)
				cooldowns.put(group, remaining);
		});
		
		return new Kit(slots, inventory.getSelectedSlot(), cooldowns,
			MC.player.getAttributeBaseValue(Attributes.ATTACK_SPEED),
			MC.player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE));
	}
	
	public int selected()
	{
		return selected;
	}
	
	public ItemStack get(int slot)
	{
		return slots.get(slot);
	}
	
	public ItemStack held()
	{
		return get(selected);
	}
	
	/** Ticks until {@code stack} can be used again, 0 if it is ready. */
	public int cooldown(ItemStack stack)
	{
		Identifier group = MC.player.getCooldowns().getCooldownGroup(stack);
		return cooldowns.getOrDefault(group, 0);
	}
	
	public boolean isReady(ItemStack stack)
	{
		return cooldown(stack) == 0;
	}
	
	/** @return a hotbar slot holding wind charges that are ready, or -1 */
	public int windCharge()
	{
		return readySlot(s -> s.is(Items.WIND_CHARGE));
	}
	
	/** @return a hotbar slot holding ender pearls that are ready, or -1 */
	public int enderPearl()
	{
		return readySlot(s -> s.is(Items.ENDER_PEARL));
	}
	
	/** @return the hotbar slot of the spear with the best Lunge, or -1 */
	public int lungeSpear()
	{
		return best(Hotbar::lungeLevel);
	}
	
	/** See {@link Hotbar#hand()}. */
	public int hand()
	{
		if(Hotbar.isHand(held()))
			return selected;
		
		int any = -1;
		int empty = -1;
		for(int i = 0; i < slots.size(); i++)
		{
			ItemStack stack = get(i);
			if(!Hotbar.isHand(stack))
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
	
	/** @return the hotbar slot of the mace with the best Wind Burst, or -1 */
	public int mace()
	{
		return best(s -> s.is(Items.MACE)
			? 1 + Loadout.level(s, Enchantments.WIND_BURST) : 0);
	}
	
	/** @return the hotbar slot of the spear with the most reach, or -1 */
	public int spear()
	{
		return best(s -> s.has(DataComponents.PIERCING_WEAPON)
			? Loadout.reach(s).maxReach() : 0);
	}
	
	/** @return the hotbar slot of the strongest sword, or -1 */
	public int sword()
	{
		return best(s -> s.is(ItemTags.SWORDS) ? attackDamage(s) : 0);
	}
	
	/** @return the hotbar slot of an item that disables shields, or -1 */
	public int axe()
	{
		return best(Loadout::disableBlockingSeconds);
	}
	
	public int slotFor(PreItem pre)
	{
		return switch(pre)
		{
			case SPEAR -> spear();
			case SWORD -> sword();
			case AXE -> axe();
			case HAND -> hand();
		};
	}
	
	/**
	 * Ticks for a full attack charge while holding {@code stack}, i.e.
	 * {@code Player.getCurrentItemAttackStrengthDelay()} once its attributes
	 * are applied.
	 */
	public double attackDelay(ItemStack stack)
	{
		return 20 / attribute(stack, Attributes.ATTACK_SPEED, baseAttackSpeed);
	}
	
	/** The {@code ATTACK_DAMAGE} attribute while holding {@code stack}. */
	public double attackDamage(ItemStack stack)
	{
		return attribute(stack, Attributes.ATTACK_DAMAGE, baseAttackDamage);
	}
	
	/**
	 * The base value plus the stack's flat main hand modifiers, ignoring
	 * effects and other equipment.
	 */
	private static double attribute(ItemStack stack,
		Holder<Attribute> attribute, double base)
	{
		double[] value = {base};
		stack.forEachModifier(EquipmentSlot.MAINHAND, (attr, modifier) -> {
			if(attr.is(attribute) && modifier
				.operation() == AttributeModifier.Operation.ADD_VALUE)
				value[0] += modifier.amount();
		});
		return value[0];
	}
	
	private int readySlot(Predicate<ItemStack> item)
	{
		for(int i = 0; i < slots.size(); i++)
		{
			ItemStack stack = get(i);
			if(item.test(stack) && isReady(stack))
				return i;
		}
		return -1;
	}
	
	private int best(ToDoubleFunction<ItemStack> score)
	{
		int best = -1;
		double bestScore = 0;
		for(int i = 0; i < slots.size(); i++)
		{
			double s = score.applyAsDouble(get(i));
			if(s > bestScore)
			{
				best = i;
				bestScore = s;
			}
		}
		return best;
	}
	
	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder("Kit[selected=" + selected);
		for(int i = 0; i < slots.size(); i++)
			if(!get(i).isEmpty())
				sb.append(' ').append(i).append('=').append(get(i).getCount())
					.append(' ').append(get(i).getItem());
		cooldowns.forEach((group, ticks) -> sb.append(" cd:").append(group)
			.append('=').append(ticks));
		return sb.append(']').toString();
	}
}
