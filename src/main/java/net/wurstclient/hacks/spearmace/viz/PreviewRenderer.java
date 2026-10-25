/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.viz;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.model.Event;
import net.wurstclient.hacks.spearmace.model.Transition;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Pending;
import net.wurstclient.hacks.spearmace.state.Pending.PearlInFlight;
import net.wurstclient.hacks.spearmace.state.Pending.Teleport;
import net.wurstclient.hacks.spearmace.state.Pending.WindChargeInFlight;
import net.wurstclient.hacks.spearmace.state.TargetState;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;

/**
 * Draws a {@link Transition} rollout in the world: the paths of the player
 * and the target, the player's projectiles, pending teleports and a marker
 * for each {@link Event}.
 */
public enum PreviewRenderer
{
	;
	
	private static final double MARKER = 0.2;
	private static final double PROJECTILE = 0.08;
	
	/**
	 * @param selfColor
	 *            the player's path and boxes, ARGB
	 * @param targetColor
	 *            the target's path and boxes, ARGB
	 */
	public static void draw(PoseStack matrices, GameState start,
		List<Transition> rollout, int selfColor, int targetColor)
	{
		ArrayList<Vec3> selfPath = new ArrayList<>();
		ArrayList<Vec3> targetPath = new ArrayList<>();
		ArrayList<ColoredBox> boxes = new ArrayList<>();
		add(start, selfPath, targetPath);
		
		GameState last = start;
		for(Transition t : rollout)
		{
			GameState next = t.next();
			add(next, selfPath, targetPath);
			for(Event event : t.events())
				boxes.add(marker(event, next));
			for(Pending p : next.self().pending())
				addPending(p, boxes);
			last = next;
		}
		
		// a box around the camera would only be in the way
		RenderUtils.drawCurvedLine(matrices, selfPath, selfColor, false);
		if(last.self().pos().distanceTo(start.self().pos()) > 1)
			boxes.add(
				new ColoredBox(PlayerSim.box(last.self().pos()), selfColor));
		
		if(last.target() != null)
		{
			RenderUtils.drawCurvedLine(matrices, targetPath, targetColor,
				false);
			boxes.add(new ColoredBox(last.target().box(), targetColor));
		}
		
		RenderUtils.drawOutlinedBoxes(matrices, boxes, false);
	}
	
	/** Draws a path the player really took, to compare with a prediction. */
	public static void drawTrail(PoseStack matrices, List<Vec3> trail,
		int color)
	{
		RenderUtils.drawCurvedLine(matrices, trail, color, false);
	}
	
	private static void add(GameState state, List<Vec3> selfPath,
		List<Vec3> targetPath)
	{
		selfPath.add(state.self().pos());
		if(state.target() != null)
			targetPath.add(state.target().pos());
	}
	
	private static void addPending(Pending p, List<ColoredBox> boxes)
	{
		switch(p)
		{
			case PearlInFlight pearl -> boxes
				.add(new ColoredBox(cube(pearl.pos(), PROJECTILE), 0xFFB040FF));
			case WindChargeInFlight charge -> boxes.add(
				new ColoredBox(cube(charge.pos(), PROJECTILE), 0xFF80E0FF));
			case Teleport t when !t.applied() -> boxes
				.add(new ColoredBox(PlayerSim.box(t.to()), 0x80B040FF));
			default ->
				{
				}
		}
	}
	
	/** A small box where {@code event} happened, colored by kind. */
	private static ColoredBox marker(Event event, GameState state)
	{
		Vec3 at = state.self().pos().add(0, PlayerSim.HEIGHT / 2, 0);
		TargetState target = state.target();
		if(target != null && (event == Event.STRUCK || event == Event.BLOCKED
			|| event == Event.SHIELD_DISABLED))
			at = target.box().getCenter();
		
		return new ColoredBox(cube(at, MARKER), color(event));
	}
	
	public static int color(Event event)
	{
		return switch(event)
		{
			case THREW_WIND_CHARGE, THREW_PEARL -> 0xFFFF9020;
			case EXPLODED -> 0xFF40E0E0;
			case IMPULSE -> 0xFFFFFF40;
			case TELEPORTED, PEARL_LANDED -> 0xFFB040FF;
			case STRUCK -> 0xFFFF3030;
			case BLOCKED, MISSED -> 0xFF808080;
			case SHIELD_DISABLED -> 0xFFFFC000;
			case JABBED -> 0xFF60A0FF;
			case APEX -> 0xFFFFFFFF;
			case LANDED -> 0xFF40FF40;
		};
	}
	
	private static AABB cube(Vec3 center, double halfSize)
	{
		return new AABB(center, center).inflate(halfSize);
	}
}
