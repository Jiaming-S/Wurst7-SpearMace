/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.Level;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Hold;
import net.wurstclient.hacks.spearmace.combat.Engagement;
import net.wurstclient.hacks.spearmace.combat.EscapeLunge;
import net.wurstclient.hacks.spearmace.combat.Loadout.PreItem;
import net.wurstclient.hacks.spearmace.combat.MaceStrike;
import net.wurstclient.hacks.spearmace.combat.StrikeSelector;
import net.wurstclient.hacks.spearmace.combat.StrikeSelector.Decision;
import net.wurstclient.hacks.spearmace.combat.StunSlam;
import net.wurstclient.hacks.spearmace.maneuver.WindChargeJump;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.state.SelfState;

/**
 * The actions worth trying from a state, in the order the rules prefer
 * them:
 * <ul>
 * <li>rising: wait for the apex</li>
 * <li>on the ground near the target: launch with a wind charge</li>
 * <li>falling: {@link StrikeSelector}'s pick, then the other strikes</li>
 * </ul>
 * Every list ends with a {@link Hold}, which always works.
 */
public final class Options
{
	/** Launches only when the target is this close, horizontally. */
	private static final double LAUNCH_RANGE = 8;
	
	/** Faster than gravity leaves a player standing still. */
	private static final double RISING = 0.1;
	
	private static final int HOLD = 10;
	private static final int HOLD_RISING = 40;
	
	public List<Action> of(Level level, GameState s)
	{
		ArrayList<Action> actions = new ArrayList<>();
		SelfState self = s.self();
		if(!s.targetAlive())
			actions.add(new Hold("no target", HOLD));
		else if(self.vel().y > RISING)
			// right after a launch the player is still on the ground
			actions.add(new Hold("rising", HOLD_RISING));
		else if(self.onGround())
		{
			if(self.kit().windCharge() >= 0
				&& horizontalDistance(s) <= LAUNCH_RANGE)
				actions.add(WindChargeJump.atFeet(false));
			actions.add(new Hold("on the ground", HOLD));
		}else
		{
			addStrikes(level, s, actions);
			actions.add(new Hold("falling", HOLD));
		}
		return actions;
	}
	
	private static void addStrikes(Level level, GameState s,
		List<Action> actions)
	{
		Decision decision = StrikeSelector.decide(Engagement.of(level, s));
		if(decision.action() != null)
			actions.add(decision.action());
		
		Kit kit = s.self().kit();
		for(PreItem pre : PreItem.values())
			addIfNew(actions, new MaceStrike(pre), kit.slotFor(pre) >= 0);
		addIfNew(actions, new StunSlam(),
			kit.axe() >= 0 && s.target().shieldRaised());
		addIfNew(actions, new EscapeLunge(),
			kit.lungeSpear() >= 0 && kit.hand() >= 0);
	}
	
	private static void addIfNew(List<Action> actions, Action action,
		boolean possible)
	{
		if(possible && !actions.contains(action))
			actions.add(action);
	}
	
	private static double horizontalDistance(GameState s)
	{
		return s.self().pos().subtract(s.target().pos()).horizontalDistance();
	}
}
