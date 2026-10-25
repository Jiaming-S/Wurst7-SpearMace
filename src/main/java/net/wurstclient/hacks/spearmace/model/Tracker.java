/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.model;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.input.KeyInput;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Follows the game from update to update with the forward model, so that
 * each observed state includes the clicks the server hasn't handled yet
 * (see {@link Observer}). Call {@link #observe} at the start of an update
 * and {@link #endUpdate} after the update's keys have been pressed.
 */
public final class Tracker
{
	private static final Minecraft MC = WurstClient.MC;
	
	private GameState observed;
	private GameState predicted;
	private TickInput lastInput;
	
	/**
	 * @param target
	 *            may be {@code null}
	 */
	public GameState observe(LivingEntity target)
	{
		GameState captured = GameState.capture(target);
		predicted = null;
		if(observed != null && lastInput != null
			&& captured.self().tick() == observed.self().tick() + 1)
			predicted = sim().step(observed, lastInput).next();
		observed = Observer.observe(captured, predicted);
		return observed;
	}
	
	/** Remembers what this update pressed, for the next prediction. */
	public void endUpdate()
	{
		lastInput = KeyInput.thisUpdate();
	}
	
	/** The state at this update, or {@code null} before the first one. */
	public GameState observed()
	{
		return observed;
	}
	
	/**
	 * What the model expected for this update, or {@code null} if it had
	 * nothing to go on.
	 */
	public GameState predicted()
	{
		return predicted;
	}
	
	public void reset()
	{
		observed = null;
		predicted = null;
		lastInput = null;
	}
	
	public static TickSim sim()
	{
		return new TickSim(MC.level, TargetModel.ballistic());
	}
}
