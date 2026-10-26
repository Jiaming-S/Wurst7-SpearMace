/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.viz;

import java.util.List;

import net.wurstclient.hacks.spearmace.model.Transition;
import net.wurstclient.hacks.spearmace.plan.SearchTrace;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Where actions and planners publish the future they expect, so that
 * SpearMacePreview can draw it. Publishing is cheap and does nothing
 * visible unless that hack is enabled.
 */
public enum Preview
{
	;
	
	/** Plans older than this many updates aren't drawn any more. */
	public static final int MAX_AGE = 40;
	
	/**
	 * @param rollout
	 *            the steps from {@code start}
	 * @param firstTicks
	 *            how many of them belong to the plan's first action
	 */
	public record Plan(String label, GameState start, List<Transition> rollout,
		int firstTicks)
	{
		public int tick()
		{
			return start.self().tick();
		}
		
		/** The steps of just the first action. */
		public List<Transition> first()
		{
			return rollout.subList(0, Math.min(firstTicks, rollout.size()));
		}
	}
	
	/**
	 * Everything a search played to find {@code plan}.
	 *
	 * @param edges
	 *            in the order they were played
	 */
	public record Tree(GameState root, List<SearchTrace.Edge> edges,
		SearchTrace trace, net.wurstclient.hacks.spearmace.plan.Plan plan,
		long nanos)
	{}
	
	private static volatile Plan plan;
	private static volatile Tree tree;
	private static volatile boolean tracing;
	
	public static void show(String label, GameState start,
		List<Transition> rollout)
	{
		show(label, start, rollout, rollout.size());
	}
	
	public static void show(String label, GameState start,
		List<Transition> rollout, int firstTicks)
	{
		plan = new Plan(label, start, List.copyOf(rollout), firstTicks);
	}
	
	public static void clear()
	{
		plan = null;
		tree = null;
	}
	
	/** Whether planners should record their search trees. */
	public static boolean isTracing()
	{
		return tracing;
	}
	
	public static void setTracing(boolean tracing)
	{
		Preview.tracing = tracing;
		if(!tracing)
			tree = null;
	}
	
	public static void showTree(GameState root, SearchTrace trace,
		net.wurstclient.hacks.spearmace.plan.Plan plan, long nanos)
	{
		tree = new Tree(root, trace.edges(), trace, plan, nanos);
	}
	
	/**
	 * The latest search tree, or {@code null}. It stays until the next
	 * search, however long ago that was, so that it can be looked at.
	 */
	public static Tree latestTree()
	{
		return tree;
	}
	
	/** The latest plan, or {@code null} if it is older than {@code now}. */
	public static Plan latest(int now)
	{
		Plan p = plan;
		return p == null || now - p.tick() > MAX_AGE ? null : p;
	}
}
