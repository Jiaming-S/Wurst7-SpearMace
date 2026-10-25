/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.model;

import net.minecraft.world.level.Level;
import net.wurstclient.hacks.spearmace.sim.TargetSim;
import net.wurstclient.hacks.spearmace.state.TargetState;

/** How the target is expected to move, one client tick at a time. */
@FunctionalInterface
public interface TargetModel
{
	public TargetState step(Level level, TargetState target);
	
	/** Keeps walking or falls, see {@link TargetSim}. */
	public static TargetModel ballistic()
	{
		return (level, target) -> {
			TargetSim sim = TargetSim.of(level, target);
			sim.step();
			return target.withMove(sim.pos, sim.vel, sim.onGround);
		};
	}
}
