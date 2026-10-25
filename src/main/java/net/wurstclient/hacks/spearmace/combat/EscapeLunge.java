/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.combat;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Step;
import net.wurstclient.hacks.spearmace.maneuver.SpearLunge;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * A flat {@link SpearLunge} straight away from the target, for when it
 * falls onto us from above and the spear can't reach it.
 */
public record EscapeLunge() implements Action
{
	@Override
	public String name()
	{
		return "EscapeLunge";
	}
	
	@Override
	public Step next(Level level, GameState s)
	{
		if(s.target() == null)
			return Step.fail("no target");
		return new SpearLunge(awayYaw(s), 0).next(level, s);
	}
	
	/** The yaw that faces away from the target, horizontally. */
	public static float awayYaw(GameState s)
	{
		Vec3 away = s.self().pos().subtract(s.target().pos());
		if(away.horizontalDistanceSqr() < 1e-4)
			return s.self().yaw();
		return (float)Math.toDegrees(Math.atan2(away.z, away.x)) - 90F;
	}
}
