/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.model;

import net.wurstclient.hacks.spearmace.sim.Move;
import net.wurstclient.util.Rotation;

/**
 * What one update does to the controls: the view, the held movement keys
 * and the clicks queued for the next tick's {@code handleKeybinds()}.
 *
 * @param rotation
 *            the view, or {@code null} to keep it
 * @param slot
 *            the hotbar key to click, or -1
 * @param use
 *            whether to click the use key
 * @param useHeld
 *            whether to hold the use key down, which eating needs
 */
public record TickInput(Rotation rotation, Move move, boolean sprint,
	boolean jump, int slot, boolean attack, boolean use, boolean useHeld)
{
	/** Keeps looking where it does and holds nothing. */
	public static final TickInput NONE =
		new TickInput(null, Move.NONE, false, false, -1, false, false, false);
	
	public TickInput withRotation(Rotation newRotation)
	{
		return new TickInput(newRotation, move, sprint, jump, slot, attack, use,
			useHeld);
	}
	
	public TickInput withSlot(int newSlot)
	{
		return new TickInput(rotation, move, sprint, jump, newSlot, attack, use,
			useHeld);
	}
	
	public TickInput withAttack()
	{
		return new TickInput(rotation, move, sprint, jump, slot, true, use,
			useHeld);
	}
	
	public TickInput withUse()
	{
		return new TickInput(rotation, move, sprint, jump, slot, attack, true,
			useHeld);
	}
	
	public TickInput withUseHeld(boolean held)
	{
		return new TickInput(rotation, move, sprint, jump, slot, attack, use,
			held);
	}
	
	public TickInput withJump()
	{
		return new TickInput(rotation, move, sprint, true, slot, attack, use,
			useHeld);
	}
	
	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder("Input[");
		if(rotation != null)
			sb.append(String.format("rot=(%.1f, %.1f)", rotation.yaw(),
				rotation.pitch()));
		if(move.isMoving())
			sb.append(" move=").append(move);
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
		if(useHeld)
			sb.append(" holdUse");
		return sb.append(']').toString();
	}
}
