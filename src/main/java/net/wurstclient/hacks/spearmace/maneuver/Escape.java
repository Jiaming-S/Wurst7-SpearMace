/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.maneuver;

import net.minecraft.world.level.Level;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Sequence;
import net.wurstclient.hacks.spearmace.action.Step;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Gets away from the target to heal: a forward wind charge jump away from
 * it, then a golden apple in the air.
 *
 * @param awayYaw
 *            the direction to launch in
 */
public record Escape(float awayYaw) implements Action
{
	@Override
	public String name()
	{
		return String.format("Escape(yaw=%.1f)", awayYaw);
	}
	
	@Override
	public Step next(Level level, GameState s)
	{
		if(s.self().kit().goldenApple() < 0)
			return Step.fail("no golden apple ready");
		return new Sequence(name(), WindChargeJump.forward(awayYaw), new Eat())
			.next(level, s);
	}
}
