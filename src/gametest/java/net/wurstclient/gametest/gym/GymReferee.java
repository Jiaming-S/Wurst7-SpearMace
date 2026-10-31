/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.gametest.gym.GymPayloads.Start;
import net.wurstclient.gametest.gym.GymPayloads.Stop;
import net.wurstclient.gametest.gym.GymRun.Settings;

/**
 * Runs the episodes of one pair of bots: resets both into their arena,
 * tells them to fight with their configs, ends the episode on a death, a
 * timeout or a bot leaving the arena, and writes one JSON line per episode
 * with the server's view of what happened. A {@link GymRun} can run
 * several of these side by side.
 */
public final class GymReferee
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("SpearMaceGym");
	
	/** Lets the bots load the world before the first episode. */
	private static final int JOIN_TICKS = 60;
	private static final int MAX_WAIT_TICKS = 20 * 300;
	
	/** Waits out the pearl cooldown and lets the bots land. */
	private static final int SETTLE_TICKS = 25;
	private static final int MAX_OUTSIDE_TICKS = 40;
	
	/** A hit taken this soon after landing one is traded back. */
	private static final int TRADE_TICKS = 60;
	
	private enum Phase
	{
		WAITING,
		SETTLING,
		FIGHTING,
		DONE;
	}
	
	private final GymRun run;
	private final MinecraftServer server;
	private final Settings settings;
	private final int pair;
	private final AABB box;
	private final Vec3 offset;
	private final Random random;
	private final String[] players;
	
	private Phase phase = Phase.WAITING;
	private int phaseTicks;
	private int waitTicks;
	private int episode;
	private boolean arenaReady;
	private final int[] wins = new int[3];
	
	private GymConfig[] configs;
	private Vec3[] starts;
	private Stats[] stats;
	private int dead = -1;
	private boolean killed;
	private final int[] outsideTicks = new int[2];
	private long msptSum;
	private long msptMax;
	
	/** What the server saw of one side during an episode. */
	private static final class Stats
	{
		double dealt;
		double dealtRaw;
		double taken;
		double fall;
		int hits;
		int blocked;
		
		/**
		 * Hits on this side that came within {@link #TRADE_TICKS} of its own.
		 */
		int traded;
		
		/** When this side last hit the other, or -1. */
		int lastHitTick = -1;
		int windCharges;
		int pearls;
		int apples;
	}
	
	/**
	 * @param pair
	 *            which pair this is, from 0
	 * @param box
	 *            this pair's arena, the run's arena moved by
	 *            {@code offset}
	 * @param players
	 *            the names of side A's and side B's players
	 */
	public GymReferee(GymRun run, int pair, AABB box, Vec3 offset,
		String[] players)
	{
		this.run = run;
		server = run.server();
		settings = run.settings();
		this.pair = pair;
		this.box = box;
		this.offset = offset;
		this.players = players;
		random = new Random(settings.seed() + pair);
		arenaReady = offset.equals(Vec3.ZERO) || !run.arena().hasBox();
	}
	
	public boolean isRunning()
	{
		return phase != Phase.DONE;
	}
	
	public int[] wins()
	{
		return wins;
	}
	
	public String status()
	{
		return String.format("%sepisode %d (%s), A %d, B %d, draws %d",
			prefix(), episode, phase.name().toLowerCase(), wins[0], wins[1],
			wins[2]);
	}
	
	public void tick()
	{
		if(phase == Phase.DONE)
			return;
		
		phaseTicks++;
		switch(phase)
		{
			case WAITING ->
			{
				if(!ready(0) || !ready(1))
				{
					phaseTicks = Math.min(phaseTicks, 1);
					if(++waitTicks > MAX_WAIT_TICKS)
						finish("the bots didn't join, see client-*.log");
				}else if(phaseTicks >= JOIN_TICKS)
					nextEpisode();
			}
			case SETTLING ->
			{
				if(phaseTicks >= SETTLE_TICKS)
					startFight();
			}
			case FIGHTING -> checkFight();
			default ->
				{
				}
		}
	}
	
	private void nextEpisode()
	{
		boolean outOfEpisodes =
			settings.episodes() > 0 && episode >= settings.episodes();
		boolean outOfTime = settings.endMillis() > 0
			&& System.currentTimeMillis() > settings.endMillis();
		if(outOfEpisodes || outOfTime)
		{
			finish("done after " + episode + " episodes");
			return;
		}
		
		// the copy needs the chunks that the run force-loaded, so it waits
		// until the bots have joined
		if(!arenaReady)
		{
			run.arena().copyTo(server, run.box(), offset);
			arenaReady = true;
		}
		
		ServerLevel level = server.overworld();
		Vec3[] s = run.arena().sampleStarts(level, box, offset, random);
		if(s == null)
		{
			finish("no room for two start points in the arena");
			return;
		}
		
		episode++;
		starts = s;
		ArrayList<Entity> leftovers = new ArrayList<>(
			level.getEntities((Entity)null, box, e -> !(e instanceof Player)));
		// a pearl still flying outside the arena would teleport its bot
		// out of the next episode
		for(Entity e : level.getAllEntities())
			if(e instanceof Projectile p && isSide(p.getOwner()))
				leftovers.add(e);
		leftovers.forEach(Entity::discard);
		for(int i = 0; i < 2; i++)
			reset(player(i), starts[i], starts[1 - i]);
		phase = Phase.SETTLING;
		phaseTicks = 0;
	}
	
	private void reset(ServerPlayer player, Vec3 start, Vec3 facing)
	{
		player.setGameMode(GameType.SURVIVAL);
		run.arena().giveKit(server, player, settings.apples(),
			settings.armor());
		String uuid = player.getStringUUID();
		command(server, "attribute " + uuid + " minecraft:max_health base set "
			+ settings.health());
		command(server, "effect clear " + uuid);
		command(server, "effect give " + uuid + " saturation infinite 0 true");
		player.setHealth(player.getMaxHealth());
		player.resetFallDistance();
		player.clearFire();
		for(var group : Set.copyOf(player.getCooldowns().cooldowns.keySet()))
			player.getCooldowns().removeCooldown(group);
		
		Vec3 d = facing.subtract(start);
		float yaw = (float)(Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90;
		player.teleportTo(server.overworld(), start.x, start.y, start.z,
			Set.of(), yaw, 0, true);
		player.setDeltaMovement(Vec3.ZERO);
		player.needsSync = true;
	}
	
	private void startFight()
	{
		if(player(0) == null || player(1) == null)
		{
			phase = Phase.WAITING;
			phaseTicks = 0;
			episode--;
			return;
		}
		
		configs = new GymConfig[2];
		configs[0] = acting(settings.specA());
		configs[1] = settings.mirror() ? configs[0] : acting(settings.specB());
		GymConfig expert = settings.expert();
		stats = new Stats[]{new Stats(), new Stats()};
		dead = -1;
		killed = false;
		outsideTicks[0] = outsideTicks[1] = 0;
		msptSum = msptMax = 0;
		for(int i = 0; i < 2; i++)
		{
			ServerPlayer p = player(i);
			stats[i].windCharges = count(p, Items.WIND_CHARGE);
			stats[i].pearls = count(p, Items.ENDER_PEARL);
			stats[i].apples = count(p, Items.GOLDEN_APPLE);
			ServerPlayNetworking.send(p,
				new Start(episode, fileSide(i), players[1 - i],
					configs[i].toJson(), expert == null ? "" : expert.toJson(),
					settings.explore(), random.nextLong(),
					settings.outDir().toString()));
		}
		run.updateScoreboard();
		phase = Phase.FIGHTING;
		phaseTicks = 0;
	}
	
	/**
	 * The acting config for {@code spec}, with a budget for the run's tick
	 * rate. {@code expert} acts as the expert itself.
	 */
	private GymConfig acting(String spec)
	{
		if(spec.equalsIgnoreCase("expert") && settings.expert() != null)
			return settings.expert();
		
		int budget = Math.round(GymConfig.BUDGET * 20 / settings.tickRate());
		return GymConfig.parse(spec, random).withBudget(budget);
	}
	
	private void checkFight()
	{
		long mspt = server.getAverageTickTimeNanos() / 1_000_000;
		msptSum += mspt;
		msptMax = Math.max(msptMax, mspt);
		
		for(int i = 0; i < 2; i++)
		{
			ServerPlayer p = player(i);
			if(p == null)
			{
				endFight("disconnected", 1 - i);
				return;
			}
			outsideTicks[i] =
				box.contains(p.position()) ? 0 : outsideTicks[i] + 1;
		}
		
		if(dead >= 0)
			endFight(killed ? "killed" : "died", 1 - dead);
		else if(outsideTicks[0] > MAX_OUTSIDE_TICKS
			|| outsideTicks[1] > MAX_OUTSIDE_TICKS)
		{
			boolean a = outsideTicks[0] > MAX_OUTSIDE_TICKS;
			boolean b = outsideTicks[1] > MAX_OUTSIDE_TICKS;
			endFight("left arena", a && b ? -1 : a ? 1 : 0);
		}else if(phaseTicks >= settings.maxTicks())
		{
			float a = player(0).getHealth();
			float b = player(1).getHealth();
			endFight("timeout", a == b ? -1 : a > b ? 0 : 1);
		}
	}
	
	/**
	 * @param winner
	 *            0 for A, 1 for B, -1 for a draw
	 */
	private void endFight(String reason, int winner)
	{
		for(int i = 0; i < 2; i++)
		{
			ServerPlayer p = player(i);
			if(p != null)
				ServerPlayNetworking.send(p, new Stop(episode, reason));
		}
		wins[winner < 0 ? 2 : winner]++;
		writeSummary(reason, winner);
		LOGGER.info("{}Episode {}: {}, winner {}", prefix(), episode, reason,
			winner < 0 ? "none" : side(winner));
		run.updateScoreboard();
		nextEpisode();
	}
	
	private void writeSummary(String reason, int winner)
	{
		JsonObject json = new JsonObject();
		json.addProperty("pair", pair);
		json.addProperty("episode", episode);
		json.addProperty("seed", settings.seed());
		json.addProperty("reason", reason);
		json.addProperty("winner", winner < 0 ? null : side(winner));
		json.addProperty("ticks", phaseTicks);
		json.addProperty("tickRate", settings.tickRate());
		json.addProperty("explore", settings.explore());
		json.add("expert", GymJson.of(settings.expert()));
		json.addProperty("msptMean", msptSum / (double)Math.max(1, phaseTicks));
		json.addProperty("msptMax", msptMax);
		JsonArray arenaBox = new JsonArray();
		arenaBox.add(GymJson.vec(box.getMinPosition()));
		arenaBox.add(GymJson.vec(box.getMaxPosition()));
		json.add("arena", arenaBox);
		
		for(int i = 0; i < 2; i++)
		{
			ServerPlayer p = player(i);
			Stats s = stats[i];
			JsonObject side = new JsonObject();
			side.addProperty("player", players[i]);
			side.addProperty("file", "decisions-" + fileSide(i) + ".jsonl");
			side.add("config", GymJson.of(configs[i]));
			side.addProperty("label", configs[i].label());
			side.add("start", GymJson.vec(starts[i]));
			side.addProperty("dealt", s.dealt);
			side.addProperty("dealtRaw", s.dealtRaw);
			side.addProperty("taken", s.taken);
			side.addProperty("fall", s.fall);
			side.addProperty("hits", s.hits);
			side.addProperty("traded", s.traded);
			side.addProperty("blocked", s.blocked);
			if(p != null)
			{
				side.addProperty("windChargesUsed",
					s.windCharges - count(p, Items.WIND_CHARGE));
				side.addProperty("pearlsUsed",
					s.pearls - count(p, Items.ENDER_PEARL));
				side.addProperty("applesEaten",
					s.apples - count(p, Items.GOLDEN_APPLE));
				side.addProperty("endHealth", p.getHealth());
				side.add("end", GymJson.vec(p.position()));
			}
			json.add(side(i), side);
		}
		
		try
		{
			Files.createDirectories(settings.outDir());
			Files.writeString(settings.outDir().resolve("episodes.jsonl"),
				json + System.lineSeparator(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}catch(IOException e)
		{
			LOGGER.error("Can't write the episode summary", e);
		}
	}
	
	/** Stops this pair's episodes and its bots. */
	public void finish(String why)
	{
		if(phase == Phase.DONE)
			return;
		
		if(phase == Phase.FIGHTING)
			for(int i = 0; i < 2; i++)
			{
				ServerPlayer p = player(i);
				if(p != null)
					ServerPlayNetworking.send(p, new Stop(episode, why));
			}
		phase = Phase.DONE;
		run.broadcast(String.format("%sstopped: %s. A %d, B %d, draws %d",
			prefix(), why, wins[0], wins[1], wins[2]));
	}
	
	/** Called before a player would die. */
	public boolean allowDeath(LivingEntity entity, DamageSource source)
	{
		int i = sideOf(entity);
		if(i < 0 || phase == Phase.WAITING)
			return true;
			
		// the next reset heals the bot anyway, this just avoids the
		// death screen
		entity.setHealth(entity.getMaxHealth());
		if(phase == Phase.FIGHTING && dead < 0)
		{
			dead = i;
			killed = source.getEntity() == player(1 - i);
		}
		return false;
	}
	
	public void afterDamage(LivingEntity entity, DamageSource source,
		float baseDamage, float damageTaken, boolean blocked)
	{
		int i = sideOf(entity);
		if(i < 0 || phase != Phase.FIGHTING)
			return;
		
		stats[i].taken += damageTaken;
		if(source.is(DamageTypeTags.IS_FALL))
			stats[i].fall += damageTaken;
		if(source.getEntity() == player(1 - i))
		{
			Stats attacker = stats[1 - i];
			attacker.dealt += damageTaken;
			attacker.dealtRaw += baseDamage;
			attacker.hits++;
			attacker.lastHitTick = phaseTicks;
			if(stats[i].lastHitTick >= 0
				&& phaseTicks - stats[i].lastHitTick <= TRADE_TICKS)
				stats[i].traded++;
			if(blocked)
				attacker.blocked++;
		}
	}
	
	/** One line of the run's scoreboard. */
	public String label()
	{
		return configs == null ? prefix() + "waiting" : prefix() + "#" + episode
			+ " " + configs[0].label() + " vs " + configs[1].label();
	}
	
	private String prefix()
	{
		return settings.pairs() > 1 ? "pair " + pair + ": " : "";
	}
	
	/** Whether a bot is in the world, past its client's loading screen. */
	private boolean ready(int side)
	{
		ServerPlayer p = player(side);
		return p != null && p.connection.hasClientLoaded();
	}
	
	private ServerPlayer player(int side)
	{
		return server.getPlayerList().getPlayerByName(players[side]);
	}
	
	public boolean isSide(Entity e)
	{
		return sideOf(e) >= 0;
	}
	
	private int sideOf(Entity e)
	{
		if(!(e instanceof ServerPlayer p))
			return -1;
		String name = p.getScoreboardName();
		return name.equals(players[0]) ? 0 : name.equals(players[1]) ? 1 : -1;
	}
	
	private static String side(int i)
	{
		return i == 0 ? "A" : "B";
	}
	
	/** The side as the bot's data file names it, with the pair if several. */
	private String fileSide(int i)
	{
		return (settings.pairs() > 1 ? Integer.toString(pair) : "") + side(i);
	}
	
	private static int count(ServerPlayer player, Item item)
	{
		Inventory inv = player.getInventory();
		int count = 0;
		for(int i = 0; i < inv.getContainerSize(); i++)
			if(inv.getItem(i).is(item))
				count += inv.getItem(i).getCount();
		return count;
	}
	
	/** Runs {@code command} as the server, without chat output. */
	public static void command(MinecraftServer server, String command)
	{
		server.getCommands().performPrefixedCommand(
			server.createCommandSourceStack().withSuppressedOutput(), command);
	}
}
