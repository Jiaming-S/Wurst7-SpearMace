/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.WurstClient;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.util.Rotation;

/**
 * The only way maneuvers control the player: real view rotation plus
 * simulated key clicks and held keys, just like a human would use them.
 *
 * <p>
 * Clicks queued during an {@code UpdateEvent} are consumed by the next
 * tick's {@code Minecraft.handleKeybinds()}, which handles hotbar keys
 * before attack and use clicks. A rotation set during an {@code UpdateEvent}
 * reaches the server with that same tick's movement packet, so it is in
 * place when the clicks are sent.
 */
public enum KeyInput
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	
	public static void selectSlot(int slot)
	{
		click(MC.options.keyHotbarSlots[slot]);
	}
	
	public static void clickAttack()
	{
		click(MC.options.keyAttack);
	}
	
	public static void clickUse()
	{
		click(MC.options.keyUse);
	}
	
	public static void holdJump(boolean held)
	{
		if(held)
			MC.options.keyJump.setDown(true);
		else
			IKeyMapping.get(MC.options.keyJump).resetDownState();
	}
	
	public static void look(Rotation rotation)
	{
		rotation.applyToClientPlayer();
	}
	
	/**
	 * Whether an attack click queued now would be accepted by the client
	 * next tick. Mirrors the check in {@code Minecraft.startAttack()}, which
	 * still uses the attack speed of the item held <i>now</i>, even if the
	 * same tick's hotbar click switches to {@code weapon} first.
	 */
	public static boolean canAttackNextTick(ItemStack weapon)
	{
		return ticksUntilAttack(weapon) == 0;
	}
	
	/**
	 * How many more ticks to wait before queuing an attack click with
	 * {@code weapon}, assuming the held item doesn't change.
	 */
	public static int ticksUntilAttack(ItemStack weapon)
	{
		for(int wait = 0; wait < 100; wait++)
			if(!MC.player.cannotAttackWithItem(weapon, wait + 1))
				return wait;
			
		return 100;
	}
	
	private static void click(KeyMapping key)
	{
		IKeyMapping.get(key).simulateClick();
	}
}
