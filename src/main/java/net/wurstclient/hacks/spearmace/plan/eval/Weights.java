/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan.eval;

/**
 * What {@link DamageRace} cares about, in units of target damage.
 *
 * @param fallDamage
 *            per point of fall damage the player takes
 * @param windCharge
 *            per wind charge thrown
 * @param pearl
 *            per ender pearl thrown
 * @param tick
 *            per tick, so that the same damage sooner is better
 * @param height
 *            per block of the player's feet above the target's eyes, which
 *            the next smash turns into damage
 * @param maxHeight
 *            height above this isn't worth more
 * @param ready
 *            for a wind charge being ready, to launch again
 * @param distance
 *            per block of horizontal distance beyond strike range
 */
public record Weights(double fallDamage, double windCharge, double pearl,
	double tick, double height, double maxHeight, double ready, double distance)
{
	public static final Weights DEFAULT =
		new Weights(2, 1, 3, 0.05, 1, 30, 2, 1);
}
