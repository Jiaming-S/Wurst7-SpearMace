/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan.search;

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
 * Looks {@code depth} decision points ahead, keeping the {@code width}
 * best action sequences at each depth, ranked by their score so far plus
 * the value of where they end. The first level is always searched; deeper
 * ones only while the budget lasts.
 */
public final class BeamSearch implements Search
{
	private final int width;
	private final int depth;
	
	public BeamSearch(int width, int depth)
	{
		this.width = width;
		this.depth = depth;
	}
	
	private record Node(GameState state, List<Outcome> path, double score,
		double rank)
	{
		Node extend(Outcome o, SearchProblem p)
		{
			ArrayList<Outcome> longer = new ArrayList<>(path);
			longer.add(o);
			double sum = score + p.score(o);
			return new Node(o.end(), longer, sum, sum + p.value(o.end()));
		}
	}
	
	@Override
	public Plan search(SearchProblem p, Budget budget)
	{
		GameState root = p.root();
		List<Node> beam = List.of(new Node(root, List.of(), 0, p.value(root)));
		int reached = 0;
		for(int d = 0; d < depth && (d == 0 || budget.left()); d++)
		{
			ArrayList<Node> next = new ArrayList<>();
			for(Node n : beam)
			{
				if(p.isTerminal(n.state()))
				{
					next.add(n);
					continue;
				}
				
				for(Action a : p.actions(n.state()))
				{
					Outcome o = p.play(n.state(), a);
					if(!o.failed())
						next.add(n.extend(o, p));
				}
			}
			if(next.isEmpty())
				break;
			
			next.sort(Comparator.comparingDouble(Node::rank).reversed());
			beam = next.subList(0, Math.min(width, next.size()));
			reached = d + 1;
			for(int i = 0; i < beam.size(); i++)
				if(beam.get(i).path().size() == d + 1)
					p.note(beam.get(i).path().getLast(),
						String.format("beam d=%d #%d", d + 1, i + 1));
		}
		
		Node best = beam.getFirst();
		if(best.path().isEmpty())
			return Plan.NOTHING;
		
		List<Action> actions =
			best.path().stream().map(Outcome::action).toList();
		return new Plan(actions, best.path(), best.rank(), String
			.format("beam %dx%d, reached depth %d", width, depth, reached));
	}
}
