/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

import net.wurstclient.hacks.spearmace.action.Channel;
import net.wurstclient.hacks.spearmace.action.Controller;
import net.wurstclient.hacks.spearmace.action.Step.Run;
import net.wurstclient.hacks.spearmace.combat.Engagement;
import net.wurstclient.hacks.spearmace.combat.Loadout.PreItem;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.state.SelfState;

/**
 * What to do with the channels no action claims: look at the target,
 * release the movement keys, and while in the air hold the
 * longest-charging pre-item, so that the next strike is charged by the
 * time it starts.
 */
public final class DefaultController implements Controller
{
	@Override
	public Run fill(GameState s, Run run)
	{
		if(!s.targetAlive())
			return run.claims().contains(Channel.MOVE) ? run
				: run.moving(false);
		
		// actions that move hold the keys, nothing else should
		if(!run.claims().contains(Channel.MOVE))
			run = run.moving(false);
		
		SelfState self = s.self();
		if(!run.claims().contains(Channel.AIM))
			run = run.withRotation(
				Engagement.aim(self.eyePos(), s.target().box().getCenter()));
		
		if(!run.claims().contains(Channel.HOTBAR) && !self.onGround())
		{
			int slot = preItem(self.kit());
			if(slot >= 0 && slot != self.kit().selected())
				run = run.withSlot(slot);
		}
		return run;
	}
	
	private static int preItem(Kit kit)
	{
		for(PreItem pre : PreItem.values())
			if(kit.slotFor(pre) >= 0)
				return kit.slotFor(pre);
		return -1;
	}
}
