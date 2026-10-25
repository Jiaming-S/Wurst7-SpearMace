/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.model;

import net.wurstclient.util.Rotation;

/**
 * What one update does to the controls: the view, the held movement keys
 * and the clicks queued for the next tick's {@code handleKeybinds()}.
 *
 * @param rotation
 *            the view, or {@code null} to keep it
 * @param slot
 *            the hotbar key to click, or -1
 */
public record TickInput(Rotation rotation, boolean forward, boolean sprint,
	boolean jump, int slot, boolean attack, boolean use)
{
	/** Keeps looking where it does and holds nothing. */
	public static final TickInput NONE =
		new TickInput(null, false, false, false, -1, false, false);
	
	public TickInput withRotation(Rotation newRotation)
	{
		return new TickInput(newRotation, forward, sprint, jump, slot, attack,
			use);
	}
	
	public TickInput withSlot(int newSlot)
	{
		return new TickInput(rotation, forward, sprint, jump, newSlot, attack,
			use);
	}
	
	public TickInput withAttack()
	{
		return new TickInput(rotation, forward, sprint, jump, slot, true, use);
	}
	
	public TickInput withUse()
	{
		return new TickInput(rotation, forward, sprint, jump, slot, attack,
			true);
	}
	
	public TickInput withJump()
	{
		return new TickInput(rotation, forward, sprint, true, slot, attack,
			use);
	}
	
	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder("Input[");
		if(rotation != null)
			sb.append(String.format("rot=(%.1f, %.1f)", rotation.yaw(),
				rotation.pitch()));
		if(forward)
			sb.append(" forward");
		if(sprint)
			sb.append(" sprint");
		if(jump)
			sb.append(" jump");
		if(slot >= 0)
			sb.append(" slot=").append(slot);
		if(attack)
			sb.append(" attack");
		if(use)
			sb.append(" use");
		return sb.append(']').toString();
	}
}
