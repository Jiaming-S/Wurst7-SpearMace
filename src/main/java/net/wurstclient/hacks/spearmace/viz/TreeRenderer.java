/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.viz;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstRenderLayers;
import net.wurstclient.hacks.spearmace.model.Event;
import net.wurstclient.hacks.spearmace.model.Transition;
import net.wurstclient.hacks.spearmace.plan.Outcome;
import net.wurstclient.hacks.spearmace.plan.SearchTrace.Edge;
import net.wurstclient.hacks.spearmace.viz.Preview.Tree;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.WurstBufferSource;

/**
 * Draws a {@link Tree}: the player's path for every action the search
 * played, the chosen plan on top, and a summary of the alternatives for
 * the HUD.
 */
public enum TreeRenderer
{
	;
	
	private static final double NODE = 0.1;
	private static final double MARKER = 0.15;
	private static final int CHOSEN = 0xFFFFFFFF;
	private static final int FAILED = 0x60808080;
	private static final int ALPHA = 0xA0;
	private static final int ROLLOUT_ALPHA = 0x40;
	
	/** How the edges that weren't chosen are colored. */
	public enum Coloring
	{
		DEPTH("Depth"),
		RETURN("Return");
		
		private final String name;
		
		private Coloring(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	/**
	 * @param maxEdges
	 *            the most edges to draw besides the chosen ones, the first
	 *            ones the search played first
	 */
	public static void draw(PoseStack matrices, Tree tree, Coloring coloring,
		boolean showFailed, boolean showRollouts, int maxEdges)
	{
		Set<Outcome> chosen = chosen(tree);
		double lo = Double.MAX_VALUE;
		double hi = -Double.MAX_VALUE;
		for(Edge e : tree.edges())
			if(!e.failed())
			{
				lo = Math.min(lo, e.rank());
				hi = Math.max(hi, e.rank());
			}
		
		WurstBufferSource bs = new WurstBufferSource();
		VertexConsumer buffer = bs.getBuffer(WurstRenderLayers.getLines(false));
		Vec3 offset = RenderUtils.getCameraPos().reverse();
		ArrayList<ColoredBox> boxes = new ArrayList<>();
		
		int drawn = 0;
		for(Edge e : tree.edges())
		{
			if(chosen.contains(e.outcome()))
				continue;
			boolean rollout = isRollout(tree, e);
			if(e.failed() && !showFailed || rollout && !showRollouts)
				continue;
			if(drawn++ >= maxEdges)
				break;
			
			int color = e.failed() ? FAILED
				: color(coloring, e, lo, hi, rollout ? ROLLOUT_ALPHA : ALPHA);
			RenderUtils.drawCurvedLine(matrices, buffer,
				path(e.outcome(), offset), color);
			if(!e.failed())
				boxes.add(new ColoredBox(
					cube(e.outcome().end().self().pos(), NODE), color));
		}
		
		// the chosen path last, so that it's on top
		for(Outcome o : tree.plan().expected())
		{
			RenderUtils.drawCurvedLine(matrices, buffer, path(o, offset),
				CHOSEN);
			boxes.add(new ColoredBox(cube(o.end().self().pos(), NODE), CHOSEN));
			for(Transition t : o.steps())
				for(Event event : t.events())
					boxes
						.add(new ColoredBox(cube(t.next().self().pos(), MARKER),
							PreviewRenderer.color(event)));
		}
		
		bs.uploadAndDraw();
		RenderUtils.drawOutlinedBoxes(matrices, boxes, false);
	}
	
	/**
	 * A few lines about the search: how long it took, what it picked, and
	 * the alternatives at the root and after the first chosen action.
	 */
	public static List<String> describe(Tree tree)
	{
		ArrayList<String> lines = new ArrayList<>();
		List<Edge> edges = tree.edges();
		long failed = edges.stream().filter(Edge::failed).count();
		lines.add(String.format("%s, %.1f ms, %d played, %d failed",
			tree.plan().why(), tree.nanos() / 1e6, edges.size(), failed));
		lines.add(tree.plan().toString());
		
		Set<Outcome> chosen = chosen(tree);
		lines.add("From here:");
		addChildren(tree, -1, chosen, lines);
		
		Edge first = edges.stream()
			.filter(e -> e.parent() < 0 && chosen.contains(e.outcome()))
			.findFirst().orElse(null);
		if(first != null)
		{
			lines.add("After " + first.outcome().action().name() + ":");
			addChildren(tree, first.index(), chosen, lines);
		}
		return lines;
	}
	
	private static void addChildren(Tree tree, int parent, Set<Outcome> chosen,
		List<String> lines)
	{
		tree.edges().stream().filter(e -> e.parent() == parent)
			.sorted(Comparator.comparing(Edge::failed).thenComparing(
				Comparator.comparingDouble(Edge::rank).reversed()))
			.forEach(e -> lines.add(describe(tree, e, chosen)));
	}
	
	private static String describe(Tree tree, Edge e, Set<Outcome> chosen)
	{
		Outcome o = e.outcome();
		String mark = chosen.contains(o) ? "> " : "  ";
		if(e.failed())
			return mark + o.action().name() + " FAILED: " + o.failure();
		
		double before =
			e.parent() < 0 ? 0 : tree.edges().get(e.parent()).pathScore();
		String note = tree.trace().noteOf(o);
		return mark + String.format(
			"%s: %d ticks, dealt %.1f, p=%.2f, score %.1f, rank %.1f%s",
			o.action().name(), o.ticks(), o.damageDealt(), o.chance(),
			e.pathScore() - before, e.rank(),
			note == null ? "" : " (" + note + ")");
	}
	
	private static Set<Outcome> chosen(Tree tree)
	{
		Set<Outcome> chosen =
			Collections.newSetFromMap(new IdentityHashMap<>());
		chosen.addAll(tree.plan().expected());
		return chosen;
	}
	
	private static boolean isRollout(Tree tree, Edge e)
	{
		String note = tree.trace().noteOf(e.outcome());
		return note != null && note.startsWith("rollout");
	}
	
	private static int color(Coloring coloring, Edge e, double lo, double hi,
		int alpha)
	{
		float hue = switch(coloring)
		{
			case DEPTH -> 0.55F + 0.15F * (e.depth() - 1);
			// red for the worst, green for the best
			case RETURN -> hi > lo ? (float)((e.rank() - lo) / (hi - lo)) / 3
				: 1 / 3F;
		};
		int rgb = Color.HSBtoRGB(hue, 0.8F, 1) & 0xFFFFFF;
		return alpha << 24 | rgb;
	}
	
	private static List<Vec3> path(Outcome o, Vec3 offset)
	{
		ArrayList<Vec3> path = new ArrayList<>();
		path.add(o.start().self().pos().add(offset));
		for(Transition t : o.steps())
			path.add(t.next().self().pos().add(offset));
		return path;
	}
	
	private static AABB cube(Vec3 center, double halfSize)
	{
		return new AABB(center, center).inflate(halfSize);
	}
}
