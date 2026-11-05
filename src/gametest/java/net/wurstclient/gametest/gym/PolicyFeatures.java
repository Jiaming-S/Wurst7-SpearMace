/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A {@link PolicyModel}'s inputs, computed from a decision as the gym logs
 * it ({@code self}, {@code target} and {@code candidates} with their
 * {@code params} and {@code outcome}). This mirrors
 * {@code analysis/policy_data.py} feature by feature; working from the
 * same JSON keeps the two in step, and {@link PolicyParityCheck} checks it.
 *
 * <p>
 * Missing values count as 0, as in the training data.
 */
public enum PolicyFeatures
{
	;
	
	/** The floor of the gym arena, see {@code policy_data.GROUND_Y}. */
	public static final double GROUND_Y = -60;
	
	private static final String[] TYPES =
		{"SpearLunge", "Hold", "Drift", "EscapeLunge", "PearlCatch",
			"WindChargeJump", "Eat", "MaceStrike", "Cushion", "Escape"};
	private static final String[] PRE_ITEMS = {"HAND", "SPEAR", "SWORD", "AXE"};
	private static final String[] WHYS = {"on the ground", "falling", "rising"};
	private static final String[] EVENTS =
		{"IMPULSE", "THREW_WIND_CHARGE", "LANDED", "JABBED", "APEX",
			"STOPPED_EATING", "STARTED_EATING", "THREW_PEARL", "TELEPORTED",
			"EXPLODED", "ATE", "STRUCK", "PEARL_LANDED"};
	private static final String[] KIT_ITEMS =
		{"wind_charge", "ender_pearl", "golden_apple"};
	private static final Pattern SLOT = Pattern.compile("^(.+?) x(\\d+)$");
	
	/**
	 * One row of {@code names} per candidate of {@code decision}.
	 *
	 * @throws IllegalArgumentException
	 *             if the model wants a feature this class doesn't compute
	 */
	public static double[][] of(JsonObject decision, List<String> names)
	{
		Map<String, Double> state = state(decision);
		double bearing = state.remove("_bearing");
		JsonObject self = obj(decision, "self");
		JsonObject target = obj(decision, "target");
		JsonArray candidates = decision.getAsJsonArray("candidates");
		
		double[][] rows = new double[candidates.size()][names.size()];
		for(int i = 0; i < candidates.size(); i++)
		{
			HashMap<String, Double> f = new HashMap<>(state);
			candidate(f, candidates.get(i).getAsJsonObject(), bearing, self,
				target);
			for(int j = 0; j < names.size(); j++)
			{
				Double v = f.get(names.get(j));
				if(v == null)
					throw new IllegalArgumentException(
						"Unknown policy feature: " + names.get(j));
				rows[i][j] = v;
			}
		}
		return rows;
	}
	
	private static Map<String, Double> state(JsonObject decision)
	{
		JsonObject self = obj(decision, "self");
		JsonObject target = obj(decision, "target");
		JsonObject vitals = obj(self, "vitals");
		HashMap<String, Double> f = new HashMap<>();
		
		double[] sp = vec(self, "pos");
		double[] tp = vec(target, "pos");
		boolean both = sp != null && tp != null;
		double dx = both ? tp[0] - sp[0] : 0;
		double dy = both ? tp[1] - sp[1] : 0;
		double dz = both ? tp[2] - sp[2] : 0;
		f.put("s_dx", dx);
		f.put("s_dy", dy);
		f.put("s_dz", dz);
		f.put("s_hdist", Math.hypot(dx, dz));
		f.put("s_dist", Math.sqrt(dx * dx + dy * dy + dz * dz));
		f.put("s_height", sp == null ? 0 : sp[1] - GROUND_Y);
		f.put("s_tgt_height", tp == null ? 0 : tp[1] - GROUND_Y);
		
		double[] sv = vec(self, "vel");
		double[] tv = vec(target, "vel");
		String[] axes = {"x", "y", "z"};
		for(int i = 0; i < 3; i++)
		{
			f.put("s_self_vel_" + axes[i], sv == null ? 0 : sv[i]);
			f.put("s_tgt_vel_" + axes[i], tv == null ? 0 : tv[i]);
		}
		f.put("s_self_fallDistance", num(self, "fallDistance"));
		f.put("s_self_health", num(vitals, "health"));
		f.put("s_self_absorption", num(vitals, "absorption"));
		f.put("s_tgt_health", num(target, "health"));
		f.put("s_tgt_absorption", num(target, "absorption"));
		
		Map<String, Integer> kit = kitCounts(obj(self, "kit"));
		for(String item : KIT_ITEMS)
			f.put("s_kit_" + item, (double)kit.get(item));
		
		f.put("s_self_attackStrength", num(self, "attackStrengthTicker"));
		f.put("s_tgt_hurtTime", num(target, "hurtTime"));
		f.put("s_self_useTicks", num(self, "useTicks"));
		f.put("s_self_onGround", bool(self, "onGround"));
		f.put("s_tgt_onGround", bool(target, "onGround"));
		f.put("s_tgt_shieldRaised", bool(target, "shieldRaised"));
		f.put("s_self_sprinting", bool(self, "sprinting"));
		
		// Minecraft yaw that looks at the target
		f.put("_bearing", both ? Math.toDegrees(Math.atan2(dz, dx)) - 90 : 0);
		return f;
	}
	
	private static void candidate(Map<String, Double> f, JsonObject c,
		double bearing, JsonObject self, JsonObject target)
	{
		String type = str(c, "type");
		for(String t : TYPES)
			f.put("c_type_" + t, t.equals(type) ? 1.0 : 0.0);
		
		JsonObject p = obj(c, "params");
		JsonObject aim = obj(p, "pearlAim");
		// a PearlCatch aims with pearlAim, the others with yaw/pitch
		Double yaw = has(aim, "yaw") ? opt(aim, "yaw") : opt(p, "yaw");
		Double pitch = has(aim, "pitch") ? opt(aim, "pitch") : opt(p, "pitch");
		angle(f, "c_yaw", yaw, bearing);
		angle(f, "c_away", opt(p, "awayYaw"), bearing);
		f.put("c_pitch", (pitch == null ? 0 : pitch) / 90.0);
		f.put("c_delay", num(p, "delay"));
		f.put("c_minChance", num(p, "minChance"));
		f.put("c_eat", bool(p, "eat"));
		f.put("c_jumpFirst", bool(p, "jumpFirst"));
		f.put("c_forward", bool(p, "forward"));
		f.put("c_ticks_param", num(p, "ticks"));
		String pre = str(p, "pre");
		for(String item : PRE_ITEMS)
			f.put("c_pre_" + item, item.equals(pre) ? 1.0 : 0.0);
		String why = str(p, "why");
		for(String w : WHYS)
			f.put("c_why_" + w.replace(' ', '_'), w.equals(why) ? 1.0 : 0.0);
		
		JsonObject o = obj(c, "outcome");
		f.put("o_ticks", num(o, "ticks"));
		f.put("o_dealt", num(o, "dealt"));
		f.put("o_taken", num(o, "taken"));
		f.put("o_chance", num(o, "chance"));
		f.put("o_failed", str(o, "failure") != null ? 1.0 : 0.0);
		Set<String> events = new HashSet<>();
		if(o != null && o.get("events") instanceof JsonArray a)
			for(JsonElement e : a)
				events.add(e.getAsString());
		for(String e : EVENTS)
			f.put("o_ev_" + e, events.contains(e) ? 1.0 : 0.0);
		
		double[] end = vec(o, "endSelfPos");
		double[] sp = vec(self, "pos");
		double[] tp = vec(target, "pos");
		f.put("o_end_dx", end != null && sp != null ? end[0] - sp[0] : 0);
		f.put("o_end_dy", end != null && sp != null ? end[1] - sp[1] : 0);
		f.put("o_end_dz", end != null && sp != null ? end[2] - sp[2] : 0);
		f.put("o_end_height", end != null ? end[1] - GROUND_Y : 0);
		f.put("o_end_hdist_tgt", end != null && tp != null
			? Math.hypot(tp[0] - end[0], tp[2] - end[2]) : 0);
		f.put("o_end_dy_tgt", end != null && tp != null ? tp[1] - end[1] : 0);
	}
	
	private static void angle(Map<String, Double> f, String prefix, Double yaw,
		double bearing)
	{
		double rel = yaw == null ? 0 : Math.toRadians(yaw - bearing);
		f.put(prefix + "_sin", yaw == null ? 0 : Math.sin(rel));
		f.put(prefix + "_cos", yaw == null ? 0 : Math.cos(rel));
		f.put("has_" + prefix, yaw == null ? 0.0 : 1.0);
	}
	
	private static Map<String, Integer> kitCounts(JsonObject kit)
	{
		HashMap<String, Integer> counts = new HashMap<>();
		for(String item : KIT_ITEMS)
			counts.put(item, 0);
		if(kit == null || !(kit.get("slots") instanceof JsonArray slots))
			return counts;
		for(JsonElement slot : slots)
		{
			Matcher m = SLOT.matcher(slot.getAsString());
			if(m.matches() && counts.containsKey(m.group(1)))
				counts.merge(m.group(1), Integer.parseInt(m.group(2)),
					Integer::sum);
		}
		return counts;
	}
	
	private static JsonObject obj(JsonObject o, String key)
	{
		return o != null && o.get(key) instanceof JsonObject child ? child
			: null;
	}
	
	private static boolean has(JsonObject o, String key)
	{
		return o != null && o.has(key) && !o.get(key).isJsonNull();
	}
	
	/** {@code null} if missing, unlike {@link #num}. */
	private static Double opt(JsonObject o, String key)
	{
		return has(o, key) ? Double.valueOf(o.get(key).getAsDouble()) : null;
	}
	
	private static double num(JsonObject o, String key)
	{
		return has(o, key) ? o.get(key).getAsDouble() : 0;
	}
	
	private static double bool(JsonObject o, String key)
	{
		return has(o, key) && o.get(key).getAsBoolean() ? 1 : 0;
	}
	
	private static String str(JsonObject o, String key)
	{
		return has(o, key) ? o.get(key).getAsString() : null;
	}
	
	private static double[] vec(JsonObject o, String key)
	{
		if(!has(o, key) || !(o.get(key) instanceof JsonArray a)
			|| a.size() != 3)
			return null;
		return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(),
			a.get(2).getAsDouble()};
	}
}
