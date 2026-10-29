/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * A {@link SearchProblem} that remembers every action the search played,
 * as a tree, so that SpearMacePreview can draw what the search looked at.
 * A played action's parent is the one that ended in the state it was
 * played from. The searches pass those states back as they got them, so
 * they are matched by identity.
 */
public final class SearchTrace implements SearchProblem
{
	private final SearchProblem problem;
	private final ArrayList<Edge> edges = new ArrayList<>();
	private final IdentityHashMap<GameState, Edge> byEnd =
		new IdentityHashMap<>();
	private final IdentityHashMap<Outcome, String> notes =
		new IdentityHashMap<>();
	private final IdentityHashMap<Outcome, Visits> visits =
		new IdentityHashMap<>();
	
	/** See {@link SearchProblem#visited}. */
	public record Visits(int visits, double mean)
	{}
	
	/**
	 * One action played by the search.
	 *
	 * @param parent
	 *            the index of the edge it was played after, or -1 if it was
	 *            played from the root
	 * @param depth
	 *            1 for actions played from the root
	 * @param pathScore
	 *            the scores from the root up to and including this one
	 * @param rank
	 *            {@code pathScore} plus the value of where it ends, NaN if
	 *            it failed
	 */
	public record Edge(int index, int parent, int depth, Outcome outcome,
		double pathScore, double rank)
	{
		public boolean failed()
		{
			return outcome.failed();
		}
	}
	
	public SearchTrace(SearchProblem problem)
	{
		this.problem = problem;
	}
	
	@Override
	public GameState root()
	{
		return problem.root();
	}
	
	@Override
	public List<Action> actions(GameState s)
	{
		return problem.actions(s);
	}
	
	@Override
	public Outcome play(GameState s, Action a)
	{
		Outcome o = problem.play(s, a);
		Edge parent = byEnd.get(s);
		double pathScore =
			(parent == null ? 0 : parent.pathScore()) + problem.score(o);
		double rank =
			o.failed() ? Double.NaN : pathScore + problem.value(o.end());
		Edge edge = new Edge(edges.size(), parent == null ? -1 : parent.index(),
			parent == null ? 1 : parent.depth() + 1, o, pathScore, rank);
		edges.add(edge);
		if(!o.failed())
			byEnd.putIfAbsent(o.end(), edge);
		return o;
	}
	
	@Override
	public double score(Outcome o)
	{
		return problem.score(o);
	}
	
	@Override
	public double value(GameState s)
	{
		return problem.value(s);
	}
	
	@Override
	public boolean isTerminal(GameState s)
	{
		return problem.isTerminal(s);
	}
	
	@Override
	public void note(Outcome o, String note)
	{
		notes.merge(o, note, (a, b) -> a + ", " + b);
	}
	
	@Override
	public void visited(Outcome o, int n, double mean)
	{
		visits.put(o, new Visits(n, mean));
	}
	
	/** Every played action, in the order the search played them. */
	public List<Edge> edges()
	{
		return List.copyOf(edges);
	}
	
	/**
	 * How often the search went through {@code o}, or {@code null} if it
	 * doesn't count visits.
	 */
	public Visits visitsOf(Outcome o)
	{
		return visits.get(o);
	}
	
	/** What the search noted about {@code o}, or {@code null}. */
	public String noteOf(Outcome o)
	{
		return notes.get(o);
	}
}
