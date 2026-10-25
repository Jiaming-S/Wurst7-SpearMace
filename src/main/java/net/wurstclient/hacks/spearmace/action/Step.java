/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.action;

import java.util.EnumSet;
import java.util.Set;

import net.wurstclient.hacks.spearmace.model.TickInput;
import net.wurstclient.hacks.spearmace.state.SelfState;

/** What an {@link Action} does at one update. */
public sealed interface Step
{
	/**
	 * Keep going: press {@code input} on the claimed channels now, and let
	 * {@code then} handle the next update.
	 *
	 * @param claims
	 *            the channels this update needs, including the ones
	 *            {@code input} uses
	 * @param committed
	 *            whether stopping now would waste something already
	 *            started, such as a pearl whose catching wind charge hasn't
	 *            been thrown yet
	 */
	public record Run(TickInput input, Set<Channel> claims, boolean committed,
		Action then) implements Step
	{
		public Run
		{
			claims = Set.copyOf(claims);
		}
		
		/** Also holds these channels as they are. */
		public Run holding(Channel... channels)
		{
			EnumSet<Channel> more = EnumSet.noneOf(Channel.class);
			more.addAll(claims);
			more.addAll(Set.of(channels));
			return new Run(input, more, committed, then);
		}
		
		/** Also clicks this hotbar slot. */
		public Run withSlot(int slot)
		{
			return new Run(input.withSlot(slot), claims, committed, then)
				.holding(Channel.HOTBAR);
		}
		
		public Run withThen(Action next)
		{
			return new Run(input, claims, committed, next);
		}
		
		public Run asCommitted()
		{
			return new Run(input, claims, true, then);
		}
		
		/**
		 * The whole input for this update: the claimed channels from this
		 * step, the held keys from {@code self} otherwise.
		 */
		public TickInput merged(SelfState self)
		{
			boolean move = claims.contains(Channel.MOVE);
			boolean jump = claims.contains(Channel.JUMP);
			return new TickInput(input.rotation(),
				move ? input.forward() : self.forward(),
				move ? input.sprint() : self.sprinting(),
				jump ? input.jump() : self.jump(), input.slot(), input.attack(),
				input.use());
		}
	}
	
	/**
	 * The action worked. Nothing is pressed for this update.
	 *
	 * @param result
	 *            what it measured, specific to the action
	 */
	public record Done(Object result) implements Step
	{}
	
	/** The action can't go on. Nothing is pressed for this update. */
	public record Failed(String reason) implements Step
	{}
	
	/** Presses {@code input}, claiming the channels it uses. */
	public static Run run(TickInput input, Action then)
	{
		EnumSet<Channel> claims = EnumSet.noneOf(Channel.class);
		if(input.rotation() != null)
			claims.add(Channel.AIM);
		if(input.slot() >= 0)
			claims.add(Channel.HOTBAR);
		if(input.attack())
			claims.add(Channel.ATTACK);
		if(input.use())
			claims.add(Channel.USE);
		return new Run(input, claims, false, then);
	}
	
	/** Presses nothing and claims nothing. */
	public static Run idle(Action then)
	{
		return run(TickInput.NONE, then);
	}
	
	public static Done done(Object result)
	{
		return new Done(result);
	}
	
	public static Failed fail(String reason)
	{
		return new Failed(reason);
	}
}
