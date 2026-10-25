/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.state;

import net.minecraft.world.entity.LivingEntity;

/**
 * A snapshot of the fight that the models can predict from, whether it is
 * the current one or a predicted one.
 *
 * @param target
 *            {@code null} if there is none
 */
public record GameState(SelfState self, TargetState target)
{
	/**
	 * @param target
	 *            may be {@code null}
	 */
	public static GameState capture(LivingEntity target)
	{
		return new GameState(SelfState.capture(),
			target == null ? null : TargetState.capture(target));
	}
	
	public boolean targetAlive()
	{
		return target != null && target.health() > 0;
	}
}
