/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

import java.util.List;

import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.state.GameState;

/**
 * Everything a {@link Search} may ask about the game. It hides the forward
 * model, the decision points and the controller, so any search works with
 * any {@link Evaluator}.
 */
public interface SearchProblem
{
	public GameState root();
	
	/** The actions worth trying from {@code s}, best guess first. */
	public List<Action> actions(GameState s);
	
	/** Plays {@code a} from {@code s} until it ends or a decision point. */
	public Outcome play(GameState s, Action a);
	
	/** How good the segment was, see {@link Evaluator#score}. */
	public double score(Outcome o);
	
	/** How good it is to be in {@code s}, see {@link Evaluator#value}. */
	public double value(GameState s);
	
	/** Nothing left to plan for, such as the target being dead. */
	public boolean isTerminal(GameState s);
	
	/**
	 * What the search thought of {@code o}, for {@link SearchTrace}. Does
	 * nothing unless the search tree is being shown.
	 */
	public default void note(Outcome o, String note)
	{}
	
	/**
	 * How often a visit-counting search like MCTS went through {@code o}
	 * and how good that was on average, for {@link SearchTrace}. Does
	 * nothing unless the search tree is being recorded.
	 */
	public default void visited(Outcome o, int visits, double mean)
	{}
}
