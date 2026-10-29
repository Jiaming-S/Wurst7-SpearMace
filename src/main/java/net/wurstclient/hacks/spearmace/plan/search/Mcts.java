/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan.search;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.plan.Budget;
import net.wurstclient.hacks.spearmace.plan.Outcome;
import net.wurstclient.hacks.spearmace.plan.Plan;
import net.wurstclient.hacks.spearmace.plan.Search;
import net.wurstclient.hacks.spearmace.plan.SearchProblem;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Monte Carlo tree search over decision points, with UCT selection. The
 * model is deterministic, so each edge is played once and kept. Below a
 * new node, the rollout follows the problem's first action that works for
 * {@code rolloutDepth} more segments and then adds the leaf value. The
 * root's most visited child wins.
 */
public final class Mcts implements Search
{
	private final int maxIterations;
	private final int rolloutDepth;
	private final double exploration;
	
	public Mcts(int maxIterations, int rolloutDepth, double exploration)
	{
		this.maxIterations = maxIterations;
		this.rolloutDepth = rolloutDepth;
		this.exploration = exploration;
	}
	
	private static final class Node
	{
		final GameState state;
		final Outcome via;
		final Node parent;
		final ArrayList<Node> children = new ArrayList<>();
		ArrayDeque<Action> untried;
		int visits;
		double total;
		
		Node(GameState state, Outcome via, Node parent)
		{
			this.state = state;
			this.via = via;
			this.parent = parent;
		}
		
		double mean()
		{
			return visits == 0 ? 0 : total / visits;
		}
	}
	
	@Override
	public Plan search(SearchProblem p, Budget budget)
	{
		Node root = new Node(p.root(), null, null);
		root.untried = new ArrayDeque<>(p.actions(root.state));
		double lo = Double.MAX_VALUE;
		double hi = -Double.MAX_VALUE;
		int iterations = 0;
		
		while(iterations < maxIterations && (iterations == 0 || budget.left()))
		{
			iterations++;
			
			// selection, adding up the scores on the way down
			Node node = root;
			double gain = 0;
			while(node.untried.isEmpty() && !node.children.isEmpty())
			{
				node = select(node, lo, hi);
				gain += p.score(node.via);
			}
			
			// expansion
			if(!node.untried.isEmpty() && !p.isTerminal(node.state))
			{
				Outcome o = p.play(node.state, node.untried.poll());
				if(o.failed())
					continue;
				Node child = new Node(o.end(), o, node);
				child.untried = new ArrayDeque<>(p.actions(child.state));
				node.children.add(child);
				node = child;
				gain += p.score(o);
			}
			
			gain += rollout(p, node.state);
			lo = Math.min(lo, gain);
			hi = Math.max(hi, gain);
			for(Node n = node; n != null; n = n.parent)
			{
				n.visits++;
				n.total += gain;
			}
		}
		
		noteVisits(p, root);
		if(root.children.isEmpty())
			return Plan.NOTHING;
		
		// the most visited path, as far as it goes
		ArrayList<Outcome> path = new ArrayList<>();
		Node n = root;
		while(!n.children.isEmpty())
		{
			n = n.children.stream()
				.max(Comparator.comparingInt((Node c) -> c.visits)
					.thenComparingDouble(Node::mean))
				.orElseThrow();
			path.add(n.via);
		}
		List<Action> actions = path.stream().map(Outcome::action).toList();
		return new Plan(actions, path,
			root.children.stream().mapToDouble(Node::mean).max().orElse(0),
			String.format("mcts, %d iterations", iterations));
	}
	
	private static void noteVisits(SearchProblem p, Node node)
	{
		for(Node c : node.children)
		{
			p.note(c.via, String.format("n=%d mean=%.1f", c.visits, c.mean()));
			p.visited(c.via, c.visits, c.mean());
			noteVisits(p, c);
		}
	}
	
	/** UCT, with the returns scaled to [0, 1]. */
	private Node select(Node node, double lo, double hi)
	{
		double range = Math.max(1e-9, hi - lo);
		double logN = Math.log(node.visits);
		return node.children.stream()
			.max(Comparator.comparingDouble(c -> (c.mean() - lo) / range
				+ exploration * Math.sqrt(logN / c.visits)))
			.orElseThrow();
	}
	
	/** The rule policy's return from {@code s}, plus the leaf value. */
	private double rollout(SearchProblem p, GameState s)
	{
		double gain = 0;
		for(int d = 0; d < rolloutDepth && !p.isTerminal(s); d++)
		{
			Outcome next = null;
			for(Action a : p.actions(s))
			{
				Outcome o = p.play(s, a);
				p.note(o, "rollout");
				if(!o.failed())
				{
					next = o;
					break;
				}
			}
			if(next == null)
				break;
			gain += p.score(next);
			s = next.end();
		}
		return gain + p.value(s);
	}
}
