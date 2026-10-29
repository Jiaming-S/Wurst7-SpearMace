/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.util.Random;

import com.google.gson.Gson;

/**
 * How one side's SpearMace plans: its search, the sizes of that search and
 * the evaluator's weights. Used on the server too, where Wurst isn't
 * loaded, so it only names Wurst's settings and doesn't touch them.
 *
 * @param search
 *            the "Search" setting's name for it: {@code Rules},
 *            {@code Beam search} or {@code MCTS}
 */
public record GymConfig(String search, int width, int depth, int iterations,
	int budget, double fallDamage, double windCharge, double pearl,
	double height, double distance)
{
	private static final Gson GSON = new Gson();
	
	/**
	 * At 20 ticks a second. The referee scales it to the tick rate it
	 * runs at.
	 */
	public static final int BUDGET = 50;
	
	/** SpearMace's defaults, with the weights of {@code Weights.DEFAULT}. */
	public static final GymConfig DEFAULT =
		new GymConfig("Beam search", 4, 3, 60, BUDGET, 2, 1, 3, 1, 1);
	
	/**
	 * A config from {@code spec}: {@code rules}, {@code beam:WxD},
	 * {@code mcts:N} with default weights, or {@code random} for a sample
	 * of everything.
	 */
	public static GymConfig parse(String spec, Random random)
	{
		String[] parts = spec.toLowerCase().split(":", 2);
		GymConfig d = DEFAULT;
		return switch(parts[0])
		{
			case "random" -> sample(random);
			case "rules" -> d.withSearch("Rules", d.width, d.depth,
				d.iterations);
			case "beam" ->
			{
				int width = d.width;
				int depth = d.depth;
				if(parts.length > 1)
				{
					String[] wd = parts[1].split("x", 2);
					width = Integer.parseInt(wd[0]);
					if(wd.length > 1)
						depth = Integer.parseInt(wd[1]);
				}
				yield d.withSearch("Beam search", width, depth, d.iterations);
			}
			case "mcts" -> d.withSearch("MCTS", d.width, d.depth,
				parts.length > 1 ? Integer.parseInt(parts[1]) : d.iterations);
			default -> throw new IllegalArgumentException("Unknown config: "
				+ spec + " (rules, beam:WxD, mcts:N, random)");
		};
	}
	
	/** Any search and its sizes, with each weight scaled by 0.5-1.5. */
	public static GymConfig sample(Random random)
	{
		String search =
			new String[]{"Rules", "Beam search", "MCTS"}[random.nextInt(3)];
		GymConfig d = DEFAULT;
		return new GymConfig(search, 2 + random.nextInt(7),
			1 + random.nextInt(6), 30 + 10 * random.nextInt(28), BUDGET,
			jitter(d.fallDamage, random), jitter(d.windCharge, random),
			jitter(d.pearl, random), jitter(d.height, random),
			jitter(d.distance, random));
	}
	
	private static double jitter(double value, Random random)
	{
		// the sliders' steps are 0.1 or 0.5
		return Math.round(value * (0.5 + random.nextDouble()) * 10) / 10.0;
	}
	
	private GymConfig withSearch(String search, int width, int depth,
		int iterations)
	{
		return new GymConfig(search, width, depth, iterations, budget,
			fallDamage, windCharge, pearl, height, distance);
	}
	
	/** The same config with another time budget. */
	public GymConfig withBudget(int newBudget)
	{
		return new GymConfig(search, width, depth, iterations, newBudget,
			fallDamage, windCharge, pearl, height, distance);
	}
	
	public String toJson()
	{
		return GSON.toJson(this);
	}
	
	public static GymConfig fromJson(String json)
	{
		return GSON.fromJson(json, GymConfig.class);
	}
	
	/** Short, for the scoreboard and logs. */
	public String label()
	{
		return switch(search)
		{
			case "Rules" -> "rules";
			case "Beam search" -> "beam " + width + "x" + depth;
			default -> "mcts " + iterations;
		};
	}
}
