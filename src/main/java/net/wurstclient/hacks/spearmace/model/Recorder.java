/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.world.entity.LivingEntity;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hacks.spearmace.input.KeyInput;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Records the state and the input of every update, for checking
 * {@link TickSim} against the real game with {@link Replay}. Start it after
 * whatever sends the inputs, so that it sees them in the same update.
 */
public final class Recorder implements UpdateListener
{
	private final Supplier<LivingEntity> target;
	private final ArrayList<Frame> frames = new ArrayList<>();
	
	/**
	 * @param target
	 *            supplies the target each update, or {@code null}
	 */
	public Recorder(Supplier<LivingEntity> target)
	{
		this.target = target;
	}
	
	public record Frame(GameState state, TickInput input)
	{}
	
	public void start()
	{
		WurstClient.INSTANCE.getEventManager().add(UpdateListener.class, this);
	}
	
	public void stop()
	{
		WurstClient.INSTANCE.getEventManager().remove(UpdateListener.class,
			this);
	}
	
	@Override
	public void onUpdate()
	{
		frames.add(
			new Frame(GameState.capture(target.get()), KeyInput.thisUpdate()));
	}
	
	public List<Frame> frames()
	{
		return List.copyOf(frames);
	}
}
