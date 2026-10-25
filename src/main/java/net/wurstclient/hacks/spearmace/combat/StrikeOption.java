/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.combat;

import net.wurstclient.hacks.spearmace.combat.Loadout.PreItem;

/**
 * When and how hard an attribute-swapped mace hit would land.
 *
 * @param update
 *            in how many updates the attack click gets queued (0 = now)
 * @param charged
 *            whether the pre-item is fully charged, with a tick to spare
 * @param strength
 *            the expected attack charge when the hit lands
 * @param fall
 *            the player's fall distance when the hit lands
 * @param damage
 *            the expected damage before armor
 * @param distance
 *            from the eyes to the closest point of the target
 */
public record StrikeOption(PreItem pre, int preSlot, int update,
	boolean charged, boolean crit, double strength, double fall, double damage,
	double distance)
{
	@Override
	public String toString()
	{
		return String.format(
			"%s in %d (charged=%s crit=%s strength=%.2f fall=%.2f damage=%.2f distance=%.2f)",
			pre, update, charged, crit, strength, fall, damage, distance);
	}
}
