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
import net.wurstclient.hacks.spearmace.model.TickInput;
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
	
	/** The tick of the update that queued the clicks below. */
	private static int clickTick = Integer.MIN_VALUE;
	private static int clickedSlot = -1;
	private static boolean clickedAttack;
	private static boolean clickedUse;
	
	public static void selectSlot(int slot)
	{
		click(MC.options.keyHotbarSlots[slot]);
		note();
		clickedSlot = slot;
	}
	
	public static void clickAttack()
	{
		click(MC.options.keyAttack);
		note();
		clickedAttack = true;
	}
	
	public static void clickUse()
	{
		click(MC.options.keyUse);
		note();
		clickedUse = true;
	}
	
	/**
	 * What this update has done to the controls so far, as a
	 * {@link TickInput}.
	 */
	public static TickInput thisUpdate()
	{
		boolean now = clickTick == MC.player.tickCount;
		return new TickInput(
			new Rotation(MC.player.getYRot(), MC.player.getXRot()),
			MC.options.keyUp.isDown(), MC.player.isSprinting(),
			MC.options.keyJump.isDown(), now ? clickedSlot : -1,
			now && clickedAttack, now && clickedUse);
	}
	
	private static void note()
	{
		if(clickTick == MC.player.tickCount)
			return;
		
		clickTick = MC.player.tickCount;
		clickedSlot = -1;
		clickedAttack = false;
		clickedUse = false;
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
