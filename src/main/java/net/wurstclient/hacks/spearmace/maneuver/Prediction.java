/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.maneuver;

import java.util.List;

import net.minecraft.world.phys.Vec3;

/**
 * What a maneuver expects to happen if it starts at the current tick.
 *
 * @param path
 *            the player's feet at each update, starting with the current one
 *            and ending when the maneuver is done
 * @param endVel
 *            the player's velocity at the end of {@code path}
 * @param pSuccess
 *            estimated chance that the maneuver works (it can be below 1
 *            because of random projectile spread)
 */
public record Prediction(boolean feasible, String reason, List<Vec3> path,
	Vec3 endVel, double pSuccess)
{
	public static Prediction infeasible(String reason)
	{
		return new Prediction(false, reason, List.of(), Vec3.ZERO, 0);
	}
	
	public static Prediction of(List<Vec3> path, Vec3 endVel, double pSuccess)
	{
		return new Prediction(true, null, List.copyOf(path), endVel, pSuccess);
	}
	
	public Vec3 endPos()
	{
		return path.isEmpty() ? null : path.get(path.size() - 1);
	}
	
	public int ticks()
	{
		return Math.max(0, path.size() - 1);
	}
	
	@Override
	public String toString()
	{
		if(!feasible)
			return "infeasible (" + reason + ")";
		
		return String.format("end=%s vel=%s ticks=%d p=%.2f", fmt(endPos()),
			fmt(endVel), ticks(), pSuccess);
	}
	
	static String fmt(Vec3 v)
	{
		return v == null ? "null"
			: String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
	}
}
