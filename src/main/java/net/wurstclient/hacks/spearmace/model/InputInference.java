/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.model;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.util.Rotation;

/**
 * Works out which {@link TickInput} took the player from one captured state
 * to the next, for clicks that didn't go through {@code KeyInput}, such as
 * a human playing.
 */
public enum InputInference
{
	;
	
	/**
	 * @param before
	 *            the state at update {@code n}
	 * @param after
	 *            the state at update {@code n + 1}
	 */
	public static TickInput infer(SelfState before, SelfState after)
	{
		int slot = after.kit().selected() != before.kit().selected()
			? after.kit().selected() : -1;
		
		// a throw starts the item's cooldown right away
		ItemStack held = after.kit().held();
		boolean use = (held.is(Items.WIND_CHARGE) || held.is(Items.ENDER_PEARL))
			&& before.kit().cooldown(held) == 0
			&& after.kit().cooldown(held) > 0;
		
		// an attack resets the charge at the end of the step, a held item
		// change only in the next one
		boolean attack = after.attackStrengthTicker() == 0
			&& before.attackStrengthTicker() > 0 && !before.heldChanged();
		
		// the mouse keeps moving between updates; the crosshair pick of an
		// attack happens a tick later than the movement, so it is closer to
		// the later view
		SelfState view = attack ? after : before;
		return new TickInput(new Rotation(view.yaw(), view.pitch()),
			before.move(), before.sprinting(), before.jump(), slot, attack,
			use);
	}
}
