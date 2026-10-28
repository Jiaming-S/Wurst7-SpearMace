/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.google.gson.Gson;

import net.wurstclient.hacks.SpearMaceHack;
import net.wurstclient.hacks.spearmace.plan.eval.Weights;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SliderSetting;

/**
 * How one side's SpearMace plans, as the values of its settings.
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
	
	/** Real time, so the client must keep up with 20 ticks a second. */
	private static final int BUDGET = 50;
	
	public static final GymConfig DEFAULT = new GymConfig("Beam search", 4, 3,
		60, BUDGET, Weights.DEFAULT.fallDamage(), Weights.DEFAULT.windCharge(),
		Weights.DEFAULT.pearl(), Weights.DEFAULT.height(),
		Weights.DEFAULT.distance());
	
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
	
	/**
	 * Sets SpearMace's settings to this config.
	 *
	 * @return the previous values, for {@link #restore}
	 */
	public Map<String, Object> apply(SpearMaceHack hack)
	{
		Map<String, Setting> settings = hack.getSettings();
		HashMap<String, Object> old = new HashMap<>();
		settings.forEach((key, s) -> {
			if(s instanceof SliderSetting slider)
				old.put(key, slider.getValue());
			else if(s instanceof EnumSetting<?> e)
				old.put(key, e.getSelected().toString());
		});
		
		select(settings, "search", search);
		slide(settings, "beam width", width);
		slide(settings, "lookahead", depth);
		slide(settings, "mcts iterations", iterations);
		slide(settings, "time budget", budget);
		slide(settings, "fall damage weight", fallDamage);
		slide(settings, "wind charge cost", windCharge);
		slide(settings, "pearl cost", pearl);
		slide(settings, "height value", height);
		slide(settings, "distance penalty", distance);
		// pearl catches easily carry the bots past the default range
		Setting range = settings.get("range");
		if(range instanceof SliderSetting slider)
			slider.setValue(slider.getMaximum());
		return old;
	}
	
	/** Puts back the settings that {@link #apply} changed. */
	public static void restore(SpearMaceHack hack, Map<String, Object> old)
	{
		Map<String, Setting> settings = hack.getSettings();
		old.forEach((key, value) -> {
			if(value instanceof Double d)
				slide(settings, key, d);
			else
				select(settings, key, (String)value);
		});
	}
	
	private static void select(Map<String, Setting> settings, String key,
		String value)
	{
		if(!(settings.get(key) instanceof EnumSetting<?> e)
			|| !e.setSelected(value))
			throw new IllegalStateException(
				"SpearMace has no " + key + " " + value);
	}
	
	private static void slide(Map<String, Setting> settings, String key,
		double value)
	{
		if(!(settings.get(key) instanceof SliderSetting slider))
			throw new IllegalStateException("SpearMace has no " + key);
		slider.setValue(value);
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
