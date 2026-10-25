/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.state;

import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.sim.PearlSim;
import net.wurstclient.hacks.spearmace.sim.WindChargeSim;

/**
 * Something the player started that hasn't had its effect yet. A state
 * halfway through a maneuver is defined by these as much as by the
 * player's position and velocity.
 */
public sealed interface Pending
{
	/** One of the player's own wind charges, as the client sees it. */
	public record WindChargeInFlight(int entityId, Vec3 pos, Vec3 vel, int age)
		implements Pending
	{
		public WindChargeSim toSim()
		{
			WindChargeSim sim = new WindChargeSim(pos, vel);
			sim.age = age;
			return sim;
		}
	}
	
	/** One of the player's own ender pearls, as the client sees it. */
	public record PearlInFlight(int entityId, Vec3 pos, Vec3 vel, int age)
		implements Pending
	{
		public PearlSim toSim()
		{
			return new PearlSim(pos, vel, age);
		}
	}
}
