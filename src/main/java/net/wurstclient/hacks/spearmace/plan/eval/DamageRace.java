/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan.eval;

import net.wurstclient.hacks.spearmace.plan.Evaluator;
import net.wurstclient.hacks.spearmace.plan.Outcome;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Expected damage dealt minus damage taken. No leaf value yet, so it only
 * compares what plans do, not where they leave us.
 */
public final class DamageRace implements Evaluator
{
	@Override
	public double score(Outcome o)
	{
		return o.chance() * o.damageDealt() - o.damageTaken();
	}
	
	@Override
	public double value(GameState s)
	{
		return 0;
	}
}
