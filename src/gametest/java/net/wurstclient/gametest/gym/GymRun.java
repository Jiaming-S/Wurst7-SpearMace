/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * One {@code /gym start}: a {@link GymReferee} per pair of bots, each in
 * its own copy of the arena, plus what they share: the tick rate, the
 * spectators, the bot clients and the scoreboard.
 */
public final class GymRun
{
	private static final String OBJECTIVE = "spearmace_gym";
	
	/** How often to look for crashed bot clients. */
	private static final int RESTART_CHECK_TICKS = 100;
	
	/**
	 * @param health
	 *            each bot's max health
	 * @param expert
	 *            the expert every bot labels its decisions with, or
	 *            {@code null}
	 * @param explore
	 *            how often the acting bots play a random action
	 * @param tickRate
	 *            the server's tick rate during the run; the acting bots'
	 *            budgets grow as it drops
	 */
	public record Settings(int episodes, long endMillis, int maxTicks,
		double health, String specA, String specB, boolean mirror, long seed,
		Path outDir, GymConfig expert, double explore, float tickRate,
		int pairs)
	{}
	
	private final MinecraftServer server;
	private final GymArena arena;
	private final AABB box;
	private final Settings settings;
	private final ArrayList<GymReferee> referees = new ArrayList<>();
	private final AgentProcess agents;
	private final HashMap<UUID, GameType> spectators = new HashMap<>();
	private final float oldTickRate;
	private boolean running = true;
	
	/**
	 * @param box
	 *            the first pair's arena; the others are copies of it
	 *            further along x
	 * @param players
	 *            each pair's player names, A then B
	 * @param agents
	 *            the bot clients this run started, or {@code null}
	 */
	public GymRun(MinecraftServer server, GymArena arena, AABB box,
		Settings settings, List<String[]> players, AgentProcess agents)
	{
		this.server = server;
		this.arena = arena;
		this.box = box;
		this.settings = settings;
		this.agents = agents;
		
		for(int i = 0; i < players.size(); i++)
		{
			Vec3 offset = new Vec3(i * spacing(box), 0, 0);
			GymReferee referee = new GymReferee(this, i, box.move(offset),
				offset, players.get(i));
			referees.add(referee);
			// copies of a built arena need their chunks loaded
			if(arena.hasBox())
				forceload("add", box.move(offset));
		}
		
		oldTickRate = server.tickRateManager().tickrate();
		GymReferee.command(server, "tick rate " + settings.tickRate());
	}
	
	/** How far apart the pairs' arenas are. */
	public static double spacing(AABB box)
	{
		return Math.ceil(box.getXsize()) + 64;
	}
	
	private void forceload(String action, AABB area)
	{
		GymReferee.command(server,
			String.format("forceload %s %d %d %d %d", action, (int)area.minX,
				(int)area.minZ, (int)area.maxX, (int)area.maxZ));
	}
	
	public MinecraftServer server()
	{
		return server;
	}
	
	public GymArena arena()
	{
		return arena;
	}
	
	/** The first pair's arena, which the others copy. */
	public AABB box()
	{
		return box;
	}
	
	public Settings settings()
	{
		return settings;
	}
	
	public boolean isRunning()
	{
		return running;
	}
	
	public void tick()
	{
		if(!running)
			return;
		
		makeSpectators();
		referees.forEach(GymReferee::tick);
		if(agents != null && server.getTickCount() % RESTART_CHECK_TICKS == 0)
			for(String name : agents.restartDead())
				broadcast(name + " crashed and was restarted");
			
		if(referees.stream().noneMatch(GymReferee::isRunning))
			finish("all pairs are done");
	}
	
	/** Ends the run: stops the bots, gives back game modes. */
	public void finish(String why)
	{
		if(!running)
			return;
		
		referees.forEach(r -> r.finish(why));
		running = false;
		
		spectators.forEach((uuid, mode) -> {
			ServerPlayer p = server.getPlayerList().getPlayer(uuid);
			if(p != null)
				p.setGameMode(mode);
		});
		spectators.clear();
		GymReferee.command(server, "tick rate " + oldTickRate);
		if(arena.hasBox())
			for(int i = 0; i < referees.size(); i++)
				forceload("remove", box.move(new Vec3(i * spacing(box), 0, 0)));
		if(agents != null)
			agents.stopSoon();
		
		int[] total = new int[3];
		for(GymReferee r : referees)
			for(int i = 0; i < 3; i++)
				total[i] += r.wins()[i];
		broadcast(String.format(
			"SpearMace gym stopped: %s. A %d, B %d, draws %d. Data in %s", why,
			total[0], total[1], total[2], settings.outDir()));
	}
	
	public String status()
	{
		if(!running)
			return "Not running";
		StringBuilder sb =
			new StringBuilder("Running at " + settings.tickRate() + " ticks/s");
		for(GymReferee r : referees)
			sb.append("\n").append(r.status());
		return sb.toString();
	}
	
	/** Lets everyone but the bots watch, including players who join. */
	private void makeSpectators()
	{
		for(ServerPlayer p : server.getPlayerList().getPlayers())
		{
			if(isBot(p) || spectators.containsKey(p.getUUID()))
				continue;
			spectators.put(p.getUUID(), p.gameMode());
			p.setGameMode(GameType.SPECTATOR);
		}
	}
	
	private boolean isBot(Entity e)
	{
		return referees.stream().anyMatch(r -> r.isSide(e));
	}
	
	/** Called before a player would die. */
	public boolean allowDeath(LivingEntity entity, DamageSource source)
	{
		for(GymReferee r : referees)
			if(r.isSide(entity))
				return r.allowDeath(entity, source);
		return true;
	}
	
	public void afterDamage(LivingEntity entity, DamageSource source,
		float baseDamage, float damageTaken, boolean blocked)
	{
		for(GymReferee r : referees)
			r.afterDamage(entity, source, baseDamage, damageTaken, blocked);
	}
	
	public void updateScoreboard()
	{
		Scoreboard scoreboard = server.getScoreboard();
		Objective objective = scoreboard.getObjective(OBJECTIVE);
		if(objective == null)
		{
			objective = scoreboard.addObjective(OBJECTIVE,
				ObjectiveCriteria.DUMMY, Component.literal("SpearMace gym"),
				ObjectiveCriteria.RenderType.INTEGER, false, null);
			scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
		}
		
		int[] total = new int[3];
		for(GymReferee r : referees)
			for(int i = 0; i < 3; i++)
				total[i] += r.wins()[i];
		if(referees.size() == 1)
			objective
				.setDisplayName(Component.literal(referees.get(0).label()));
		score(scoreboard, objective, "A wins", total[0]);
		score(scoreboard, objective, "B wins", total[1]);
		score(scoreboard, objective, "draws", total[2]);
	}
	
	private static void score(Scoreboard scoreboard, Objective objective,
		String name, int value)
	{
		scoreboard
			.getOrCreatePlayerScore(ScoreHolder.forNameOnly(name), objective)
			.set(value);
	}
	
	public void broadcast(String message)
	{
		server.getPlayerList().broadcastSystemMessage(
			Component.literal("[Gym] " + message), false);
	}
}
