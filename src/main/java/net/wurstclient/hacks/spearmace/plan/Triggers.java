/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

import java.util.EnumSet;

import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.hacks.spearmace.state.TargetState;

/**
 * Finds the {@link Trigger}s between two consecutive states. The runner
 * uses it on the real game and the search on predicted states, so both
 * stop at the same decision points.
 */
public enum Triggers
{
	;
	
	/** Moves this far beyond the velocity are teleports. */
	private static final double TELEPORT_DISTANCE = 2;
	
	/** An upward velocity change this big is a launch. */
	private static final double LAUNCH = 0.5;
	
	/** Prediction errors this big make the plan stale. */
	private static final double DIVERGENCE = 0.5;
	
	/**
	 * Targets this far (horizontally) from where the plan expected them
	 * make the plan stale.
	 */
	private static final double TARGET_DIVERGENCE = 1;
	
	/**
	 * @param expected
	 *            what the model predicted for {@code now}, or {@code null}
	 *            to skip the divergence check
	 * @param planned
	 *            what the current plan expected for {@code now}, or
	 *            {@code null} to skip the target divergence check
	 */
	public static EnumSet<Trigger> detect(GameState prev, GameState now,
		GameState expected, GameState planned)
	{
		EnumSet<Trigger> triggers = EnumSet.noneOf(Trigger.class);
		if(prev == null)
			return triggers;
		
		SelfState a = prev.self();
		SelfState b = now.self();
		if(a.vel().y > 0 && b.vel().y <= 0 && !b.onGround())
			triggers.add(Trigger.APEX);
		if(!a.onGround() && b.onGround())
			triggers.add(Trigger.LANDED);
		if(b.pos().distanceTo(a.pos().add(a.vel())) > TELEPORT_DISTANCE)
			triggers.add(Trigger.TELEPORTED);
		if(b.vel().y - a.vel().y > LAUNCH)
			triggers.add(Trigger.LAUNCHED);
		
		TargetState ta = prev.target();
		TargetState tb = now.target();
		if(ta == null != (tb == null)
			|| ta != null && ta.entityId() != tb.entityId())
			triggers.add(Trigger.TARGET_CHANGED);
		else if(ta != null && ta.shieldRaised() != tb.shieldRaised())
			triggers.add(
				tb.shieldRaised() ? Trigger.SHIELD_UP : Trigger.SHIELD_DOWN);
			
		// a one-tick prediction can't see a curving path, so the target is
		// compared with the plan's prediction from where it was made
		if(tb != null && planned != null && planned.target() != null
			&& planned.target().entityId() == tb.entityId()
			&& planned.target().pos().subtract(tb.pos())
				.horizontalDistance() > TARGET_DIVERGENCE)
			triggers.add(Trigger.TARGET_DIVERGED);
		
		if(expected != null
			&& expected.self().pos().distanceTo(b.pos()) > DIVERGENCE)
			triggers.add(Trigger.DIVERGED);
		return triggers;
	}
}
