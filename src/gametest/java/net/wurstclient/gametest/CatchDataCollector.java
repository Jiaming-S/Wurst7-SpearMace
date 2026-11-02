/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import static net.wurstclient.gametest.WurstClientTestHelper.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.ActionRunner;
import net.wurstclient.hacks.spearmace.action.ActionRunner.Result;
import net.wurstclient.hacks.spearmace.action.ActionRunner.StartCondition;
import net.wurstclient.hacks.spearmace.action.ActionRunner.Status;
import net.wurstclient.hacks.spearmace.action.Prediction;
import net.wurstclient.hacks.spearmace.input.KeyInput;
import net.wurstclient.hacks.spearmace.maneuver.PearlCatch;
import net.wurstclient.hacks.spearmace.maneuver.PearlCatch.Caught;
import net.wurstclient.hacks.spearmace.maneuver.PearlCatch.InFlight;
import net.wurstclient.hacks.spearmace.maneuver.PearlCatchPlanner.Plan;
import net.wurstclient.hacks.spearmace.maneuver.SpearLunge;
import net.wurstclient.hacks.spearmace.maneuver.WindChargeJump;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.util.Rotation;

/**
 * Throws pearl catches from randomized situations and writes one JSON line
 * per catch, as training data for a model of whether a catch works. Runs
 * as the {@code catchdata} group of {@link SpearMaceTest}:
 *
 * <pre>
 * WURST_SPEARMACE_TEST=catchdata ./gradlew runClientGameTestWithMods
 * </pre>
 *
 * Catches are thrown even when the planner thinks they are unlikely
 * ({@code minChance} 0), so the data also covers the cases the bot would
 * refuse. Each line has the scenario, the client's view at the pearl click
 * and at the wind charge throw (including the planner's chance), the
 * outcome, and the server's view of the pearl and the charge as ground
 * truth.
 *
 * <p>
 * Environment variables:
 * <ul>
 * <li>{@code WURST_CATCHDATA_TRIALS}: how many trials (default 300)
 * <li>{@code WURST_CATCHDATA_HOURS}: keep going this long instead
 * <li>{@code WURST_CATCHDATA_SEED}: base seed (default: the time)
 * <li>{@code WURST_CATCHDATA_DIR}: output directory (default
 * {@code spearmace-catchdata} in the project, which the gametest run
 * doesn't clean up)
 * </ul>
 */
final class CatchDataCollector
{
	private static final Logger LOGGER = WurstTest.LOGGER;
	
	private static final double GROUND_Y = -60;
	private static final int FLOOR_Y = -61;
	private static final int FLOOR_RADIUS = 10;
	private static final int MAX_TRIAL_TICKS = 300;
	
	/** Which slots hold what, see {@code SpearMaceTest.reset}. */
	private static final int MAX_START_SLOT = 6;
	
	private static final String[] KEY_SETS =
		{"W", "S", "A", "D", "WA", "WD", "SA", "SD", "WR", "WAR", "WDR", "WN",
			"SN", "AN", "WJ", "WRJ", "J", "AD", "WS", "WASD"};
	private static final String[] FLOORS =
		{"smooth_stone", "smooth_stone", "smooth_stone", "packed_ice",
			"blue_ice", "soul_sand", "slime_block", "honey_block"};
	
	private enum Kind
	{
		/** Standing still on the ground. */
		STAND,
		/** Walking with random keys held. */
		WALK,
		/** Holding jump, thrown somewhere in the jump. */
		JUMP,
		/** Wind charge launch, thrown while rising, at the apex or falling. */
		WIND_JUMP,
		/** Sprinting wind charge launch toward a random yaw. */
		WIND_FORWARD,
		/** Thrown while sliding after a lunge on the ground. */
		LUNGE_GROUND,
		/** Launch, lunge at the apex, then thrown while flying. */
		LUNGE_AIR,
		/** Teleported up and thrown while falling. */
		DROP,
		/** Teleported up with a random velocity, like a knockback. */
		DROP_MOVING,
		/** Pushed with a random velocity from the ground. */
		LAUNCHED,
		/** A second catch after the first one's teleport. */
		AFTER_CATCH,
		/** Standing on a pillar, maybe walking off its edge. */
		PILLAR_TOP,
		/** Standing or walking in a shallow pool. */
		WATER;
	}
	
	private enum Env
	{
		OPEN,
		CEILING,
		WALL,
		PILLARS,
		ROOM;
	}
	
	/**
	 * A randomized situation. {@code param} depends on the kind: the vertical
	 * velocity to throw at when airborne, or extra ticks to wait.
	 */
	private record Scenario(long seed, Kind kind, Env env, String floor,
		float faceYaw, float yaw, float pitch, int delay, int startSlot,
		String keys, int preTicks, double param, int extraTicks, double height,
		Vec3 push, String airKeys, int knockTick, Vec3 knock, int keyTick,
		String newKeys, int strays, float secondYaw, float secondPitch,
		int envSize, int envHeight, int waterDepth, float lungePitch)
	{
		JsonObject toJson()
		{
			JsonObject o = new JsonObject();
			o.addProperty("seed", seed);
			o.addProperty("kind", kind.name());
			o.addProperty("env", env.name());
			o.addProperty("floor", floor);
			o.addProperty("faceYaw", faceYaw);
			o.addProperty("yaw", yaw);
			o.addProperty("pitch", pitch);
			o.addProperty("delay", delay);
			o.addProperty("startSlot", startSlot);
			o.addProperty("keys", keys);
			o.addProperty("preTicks", preTicks);
			o.addProperty("param", param);
			o.addProperty("extraTicks", extraTicks);
			o.addProperty("height", height);
			o.add("push", vec(push));
			o.addProperty("airKeys", airKeys);
			o.addProperty("knockTick", knockTick);
			o.add("knock", vec(knock));
			o.addProperty("keyTick", keyTick);
			o.addProperty("newKeys", newKeys);
			o.addProperty("strays", strays);
			o.addProperty("secondYaw", secondYaw);
			o.addProperty("secondPitch", secondPitch);
			o.addProperty("envSize", envSize);
			o.addProperty("envHeight", envHeight);
			o.addProperty("waterDepth", waterDepth);
			o.addProperty("lungePitch", lungePitch);
			return o;
		}
	}
	
	/** One pearl catch, from the first update its pearl was in flight. */
	private static final class Trace
	{
		final int resultIndex;
		final InFlight atClick;
		final JsonObject clickEnv;
		InFlight atThrow;
		InFlight latest;
		boolean finished;
		
		// the server's view
		final Set<Integer> oldEntities = new HashSet<>();
		int polls;
		int pearlId = -1;
		int chargeId = -1;
		int pearlFirstPoll = -1;
		int chargeFirstPoll = -1;
		int serverGap = Integer.MIN_VALUE;
		double minDist = Double.POSITIVE_INFINITY;
		Vec3 pearlLastPos;
		Vec3 pearlLastVel;
		int pearlLastAge = -1;
		int pearlGonePoll = -1;
		boolean chargeAliveAtPearlEnd;
		double chargeDistAtPearlEnd = Double.NaN;
		boolean pearlHitBlockAhead;
		Vec3 playerAfterPearl;
		Vec3 playerAtPearlEnd;
		
		Trace(int resultIndex, InFlight atClick, JsonObject clickEnv)
		{
			this.resultIndex = resultIndex;
			this.atClick = atClick;
			this.clickEnv = clickEnv;
			latest = atClick;
		}
	}
	
	/** What a trial built, to be removed afterwards. */
	private record Box(BlockPos from, BlockPos to, String restore)
	{}
	
	private final ClientGameTestContext context;
	private final TestServerContext server;
	private final Runnable reset;
	private final ArrayList<Box> built = new ArrayList<>();
	
	CatchDataCollector(ClientGameTestContext context, TestServerContext server,
		Runnable reset)
	{
		this.context = context;
		this.server = server;
		this.reset = reset;
	}
	
	void collect()
	{
		int trials = intEnv("WURST_CATCHDATA_TRIALS", 300);
		String hours = System.getenv("WURST_CATCHDATA_HOURS");
		long deadline =
			hours == null ? Long.MAX_VALUE : System.currentTimeMillis()
				+ (long)(Double.parseDouble(hours) * 3_600_000);
		if(hours != null)
			trials = Integer.MAX_VALUE;
		long baseSeed = System.getenv("WURST_CATCHDATA_SEED") != null
			? Long.parseLong(System.getenv("WURST_CATCHDATA_SEED"))
			: System.currentTimeMillis();
		
		Path file = outputDir().resolve("catches-" + LocalDateTime.now()
			.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl");
		LOGGER.info("SM catchdata: writing to {} (seed {})",
			file.toAbsolutePath(), baseSeed);
		
		runCommand(server, "gamerule fall_damage false");
		runCommand(server, "gamerule drowning_damage false");
		
		int rows = 0;
		int caught = 0;
		try(BufferedWriter out =
			Files.newBufferedWriter(file, StandardCharsets.UTF_8))
		{
			for(int trial = 0; trial < trials
				&& System.currentTimeMillis() < deadline; trial++)
			{
				Scenario sc = randomScenario(new Random(baseSeed + trial),
					baseSeed + trial);
				List<JsonObject> lines;
				try
				{
					lines = runTrial(sc);
				}catch(RuntimeException e)
				{
					LOGGER.warn("SM catchdata: trial {} crashed", trial, e);
					JsonObject o = new JsonObject();
					o.addProperty("error", String.valueOf(e));
					lines = List.of(o);
				}finally
				{
					cleanUp();
				}
				
				for(JsonObject line : lines)
				{
					line.addProperty("trial", trial);
					line.add("scenario", sc.toJson());
					out.write(line.toString());
					out.newLine();
					rows++;
					if(line.has("caught") && line.get("caught").getAsBoolean())
						caught++;
				}
				out.flush();
				
				LOGGER.info(
					"SM catchdata: trial {} {} -> {} rows ({} total,"
						+ " {} caught)",
					trial, sc.kind(), lines.size(), rows, caught);
			}
		}catch(IOException e)
		{
			throw new UncheckedIOException(e);
		}
		
		LOGGER.info("SM catchdata: done, {} rows ({} caught) in {}", rows,
			caught, file.toAbsolutePath());
	}
	
	private static Path outputDir()
	{
		String env = System.getenv("WURST_CATCHDATA_DIR");
		Path dir;
		if(env != null)
			dir = Path.of(env);
		else
		{
			// the run dir, build/run/clientGameTestWithMods, is deleted
			// before every run
			Path cwd = Path.of("").toAbsolutePath();
			boolean inRunDir = cwd.endsWith("clientGameTestWithMods")
				&& cwd.getNameCount() >= 3;
			dir = (inRunDir ? cwd.getParent().getParent().getParent() : cwd)
				.resolve("spearmace-catchdata");
		}
		
		try
		{
			Files.createDirectories(dir);
		}catch(IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return dir;
	}
	
	private static int intEnv(String name, int fallback)
	{
		String value = System.getenv(name);
		return value == null ? fallback : Integer.parseInt(value);
	}
	
	private static Scenario randomScenario(Random r, long seed)
	{
		Kind kind = Kind.values()[r.nextInt(Kind.values().length)];
		Env env = r.nextDouble() < 0.45 ? Env.OPEN
			: Env.values()[1 + r.nextInt(Env.values().length - 1)];
		String floor = FLOORS[r.nextInt(FLOORS.length)];
		float faceYaw = r.nextFloat() * 360 - 180;
		
		// the catch toward the facing direction, at a fixed offset from it
		// or anywhere
		float yaw;
		double y = r.nextDouble();
		if(y < 0.4)
			yaw = faceYaw + 45 * (r.nextInt(8) - 3) + jitter(r, 5);
		else if(y < 0.6)
			yaw = faceYaw + jitter(r, 10);
		else
			yaw = r.nextFloat() * 360 - 180;
		yaw = wrap(yaw);
		
		float pitch = randomPitch(r);
		
		// diagonal catches are always instant, see PearlCatch
		int delay;
		double d = r.nextDouble();
		if(!PearlCatch.isVertical(new Rotation(yaw, pitch)))
			delay = PearlCatch.INSTANT;
		else if(d < 0.5)
			delay = 2 + r.nextInt(3);
		else if(d < 0.92)
			delay = 1 + r.nextInt(12);
		else
			delay = PearlCatch.AUTO;
		
		int startSlot = r.nextInt(MAX_START_SLOT + 1);
		String keys = KEY_SETS[r.nextInt(KEY_SETS.length)];
		int preTicks = 2 + r.nextInt(20);
		int extraTicks = r.nextInt(12);
		double height = 0;
		double param = 0;
		Vec3 push = Vec3.ZERO;
		float lungePitch = 0;
		int waterDepth = 0;
		
		switch(kind)
		{
			case JUMP -> param = -0.5 + r.nextDouble() * 0.92;
			case WIND_JUMP, WIND_FORWARD -> param = -1.8 + r.nextDouble() * 3.6;
			case LUNGE_GROUND -> lungePitch = -40 + r.nextFloat() * 50;
			case LUNGE_AIR -> lungePitch = -30 + r.nextFloat() * 100;
			case DROP ->
			{
				height = 2 + r.nextDouble() * 58;
				param = r.nextDouble() < 0.2 ? 0 : -3 * r.nextDouble();
			}
			case DROP_MOVING ->
			{
				height = 3 + r.nextDouble() * 40;
				push = new Vec3(gauss(r, 0.6), -0.5 + r.nextDouble() * 2,
					gauss(r, 0.6));
			}
			case LAUNCHED -> push =
				new Vec3(gauss(r, 0.7), r.nextDouble() * 1.6, gauss(r, 0.7));
			case PILLAR_TOP -> height = 1 + r.nextInt(12);
			case WATER -> waterDepth = 1 + r.nextInt(2);
			default ->
				{
				}
		}
		
		// movement keys while airborne, for the kinds that start on the
		// ground without them
		String airKeys = r.nextDouble() < 0.35
			? KEY_SETS[r.nextInt(KEY_SETS.length)].replace("J", "") : null;
		if(airKeys != null && airKeys.isEmpty())
			airKeys = null;
		
		// a hit or a key change after the pearl click
		int knockTick = -1;
		Vec3 knock = Vec3.ZERO;
		if(r.nextDouble() < 0.2)
		{
			knockTick = r.nextInt(Math.max(delay, 3) + 4);
			knock = new Vec3(gauss(r, 0.5), -0.3 + r.nextDouble() * 1.1,
				gauss(r, 0.5));
		}
		int keyTick = -1;
		String newKeys = null;
		if(r.nextDouble() < 0.25)
		{
			keyTick = r.nextInt(Math.max(delay, 3) + 3);
			newKeys = r.nextDouble() < 0.3 ? ""
				: KEY_SETS[r.nextInt(KEY_SETS.length)];
		}
		
		int strays = r.nextDouble() < 0.12 ? 1 + r.nextInt(3) : 0;
		float secondYaw = wrap(r.nextFloat() * 360 - 180);
		float secondPitch = randomPitch(r);
		int envSize = 2 + r.nextInt(14);
		int envHeight = 3 + r.nextInt(28);
		
		return new Scenario(seed, kind, env, floor, faceYaw, yaw, pitch, delay,
			startSlot, keys, preTicks, param, extraTicks, height, push, airKeys,
			knockTick, knock, keyTick, newKeys, strays, secondYaw, secondPitch,
			envSize, envHeight, waterDepth, lungePitch);
	}
	
	/** The pitches the bot uses, and anything else that goes up. */
	private static float randomPitch(Random r)
	{
		double p = r.nextDouble();
		if(p < 0.45)
		{
			float[] used = {-90, -45, -30};
			return Math.max(-90, used[r.nextInt(used.length)] + jitter(r, 3));
		}
		return -90 + r.nextFloat() * 95;
	}
	
	private static float jitter(Random r, float max)
	{
		return (r.nextFloat() * 2 - 1) * max;
	}
	
	private static double gauss(Random r, double sd)
	{
		return Math.max(-2, Math.min(2, r.nextGaussian() * sd));
	}
	
	private static float wrap(float yaw)
	{
		return Mth.wrapDegrees(yaw);
	}
	
	private List<JsonObject> runTrial(Scenario sc)
	{
		reset.run();
		buildEnvironment(sc);
		context.waitTicks(3);
		context.runOnClient(mc -> {
			KeyInput.look(new Rotation(sc.faceYaw(), 0));
			KeyInput.selectSlot(sc.startSlot());
		});
		
		switch(sc.kind())
		{
			case DROP, DROP_MOVING ->
			{
				runCommand(server, String.format("tp @p 0.5 %.3f 0.5",
					GROUND_Y + sc.height()));
				context.waitTicks(2);
				if(sc.kind() == Kind.DROP_MOVING)
					push(sc.push());
			}
			case PILLAR_TOP ->
			{
				runCommand(server,
					"tp @p 0.5 " + (GROUND_Y + sc.height()) + " 0.5");
				context.waitFor(mc -> mc.player.onGround(), 100);
				if(sc.keys() != null)
				{
					context.runOnClient(mc -> holdKeys(mc, sc.keys()));
					context.waitTicks(sc.preTicks());
				}
			}
			case WALK, WATER ->
			{
				context.runOnClient(mc -> holdKeys(mc, sc.keys()));
				context.waitTicks(sc.preTicks());
			}
			case JUMP -> context.runOnClient(mc -> holdKeys(mc,
				sc.keys().contains("J") ? sc.keys() : sc.keys() + "J"));
			case LAUNCHED ->
			{
				push(sc.push());
				context.waitTicks(1);
			}
			default ->
				{
				}
		}
		
		for(int i = 0; i < sc.strays(); i++)
			runCommand(server, String.format(
				"summon ender_pearl %.2f -58.5 %.2f {Motion:[%.2fd,%.2fd,%.2fd]}",
				0.5 + (i - 1) * 2.0, 0.5, Math.sin(i) * 0.4, 0.6 + 0.2 * i,
				Math.cos(i) * 0.8));
		
		ActionRunner runner = new ActionRunner(() -> null);
		Rotation aim = new Rotation(sc.yaw(), sc.pitch());
		PearlCatch pearlCatch = new PearlCatch(aim, sc.delay(), 0);
		context.runOnClient(mc -> {
			switch(sc.kind())
			{
				case JUMP -> runner.enqueueAnyway(pearlCatch,
					ready(airborneAt(sc.param()), 0));
				case WIND_JUMP ->
				{
					runner.enqueue(
						WindChargeJump.atFeet(sc.extraTicks() % 2 == 0));
					runner.enqueueAnyway(pearlCatch,
						ready(airborneAt(sc.param()), 0));
				}
				case WIND_FORWARD ->
				{
					runner.enqueue(WindChargeJump.forward(sc.faceYaw()));
					runner.enqueueAnyway(pearlCatch,
						ready(airborneAt(sc.param()), 0));
				}
				case LUNGE_GROUND ->
				{
					runner
						.enqueue(new SpearLunge(sc.faceYaw(), sc.lungePitch()));
					runner.enqueueAnyway(pearlCatch,
						ready(StartCondition.immediately(), sc.extraTicks()));
				}
				case LUNGE_AIR ->
				{
					runner.enqueue(WindChargeJump.atFeet(false));
					runner.enqueue(
						new SpearLunge(sc.faceYaw(), sc.lungePitch()),
						StartCondition.atApex());
					runner.enqueueAnyway(pearlCatch,
						ready(StartCondition.immediately(), sc.extraTicks()));
				}
				case DROP -> runner.enqueueAnyway(pearlCatch,
					ready(airborneAt(sc.param()), 0));
				case DROP_MOVING, LAUNCHED -> runner.enqueueAnyway(pearlCatch,
					ready(StartCondition.immediately(), sc.extraTicks() / 2));
				case AFTER_CATCH ->
				{
					runner.enqueueAnyway(pearlCatch);
					runner.enqueueAnyway(
						new PearlCatch(
							new Rotation(sc.secondYaw(), sc.secondPitch()),
							sc.delay(), 0),
						ready(StartCondition.immediately(), sc.extraTicks()));
				}
				default -> runner.enqueueAnyway(pearlCatch);
			}
			runner.start();
		});
		
		ArrayList<Trace> traces = new ArrayList<>();
		Set<Integer> seenEntities = new HashSet<>();
		try
		{
			for(int tick = 0; tick < MAX_TRIAL_TICKS; tick++)
			{
				context.waitTick();
				if(step(sc, runner, traces, seenEntities))
					break;
			}
		}finally
		{
			context.runOnClient(mc -> runner.stop());
		}
		
		List<Result> results = context.computeOnClient(mc -> runner.results());
		return rows(sc, results, traces);
	}
	
	/** One update of a trial. Returns whether the runner is done. */
	private boolean step(Scenario sc, ActionRunner runner, List<Trace> traces,
		Set<Integer> seenEntities)
	{
		Trace active = traces.isEmpty() || traces.getLast().finished ? null
			: traces.getLast();
		Trace[] started = new Trace[1];
		boolean idle = context.computeOnClient(mc -> {
			if(sc.airKeys() != null && !mc.player.onGround()
				&& traces.isEmpty())
				holdKeys(mc, sc.airKeys());
			
			int done = runner.results().size();
			if(active != null && done > active.resultIndex)
				active.finished = true;
			
			Action current = runner.current();
			if(current instanceof InFlight f)
			{
				Trace t = active != null && !active.finished ? active : null;
				if(t == null || t.latest.clickTick() != f.clickTick())
					started[0] =
						new Trace(done, f, environment(mc, f.plan(), f.last()));
				else
				{
					t.latest = f;
					if(t.atThrow == null && f.chargeTick() >= 0)
						t.atThrow = f;
				}
			}
			return runner.isIdle();
		});
		
		Trace trace = active;
		if(started[0] != null)
		{
			if(active != null)
				active.finished = true;
			trace = started[0];
			trace.oldEntities.addAll(seenEntities);
			traces.add(trace);
		}
		
		if(trace != null && !trace.finished)
		{
			int sinceClick = trace.polls;
			if(sinceClick == sc.knockTick())
				push(sc.knock());
			if(sinceClick == sc.keyTick())
				context.runOnClient(mc -> holdKeys(mc, sc.newKeys()));
		}
		
		// one more look after the pearl is gone, to see where the player went
		Trace observed = trace != null && (!trace.finished
			|| trace.pearlGonePoll >= 0 && trace.playerAfterPearl == null)
				? trace : null;
		server.runOnServer(mc -> observeServer(mc, observed, seenEntities));
		return idle;
	}
	
	/**
	 * The server's view of the trace's pearl and wind charge: when they
	 * spawn, how close they get, and what happens when the pearl goes.
	 */
	private static void observeServer(MinecraftServer mc, Trace t,
		Set<Integer> seenEntities)
	{
		ServerPlayer p = mc.getPlayerList().getPlayers().getFirst();
		ThrownEnderpearl pearl = null;
		WindCharge charge = null;
		for(Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(96),
			e -> e instanceof ThrownEnderpearl || e instanceof WindCharge))
		{
			if(t == null || t.oldEntities.contains(e.getId()))
			{
				seenEntities.add(e.getId());
				continue;
			}
			seenEntities.add(e.getId());
			if(e instanceof ThrownEnderpearl tp && tp.getOwner() == p
				&& (t.pearlId < 0 || t.pearlId == e.getId()))
				pearl = tp;
			else if(e instanceof WindCharge wc && wc.getOwner() == p
				&& (t.chargeId < 0 || t.chargeId == e.getId()))
				charge = wc;
		}
		if(t == null)
			return;
		
		t.polls++;
		if(pearl != null)
		{
			if(t.pearlId < 0)
			{
				t.pearlId = pearl.getId();
				t.pearlFirstPoll = t.polls;
			}
			t.pearlLastPos = pearl.position();
			t.pearlLastVel = pearl.getDeltaMovement();
			t.pearlLastAge = pearl.tickCount;
		}
		if(charge != null && t.chargeId < 0)
		{
			t.chargeId = charge.getId();
			t.chargeFirstPoll = t.polls;
		}
		if(pearl != null && charge != null)
		{
			if(t.serverGap == Integer.MIN_VALUE)
				t.serverGap = pearl.tickCount - charge.tickCount;
			t.minDist = Math.min(t.minDist,
				pearl.position().distanceTo(charge.position()));
		}
		
		if(pearl == null && t.pearlId >= 0 && t.pearlGonePoll < 0)
		{
			t.pearlGonePoll = t.polls;
			t.playerAtPearlEnd = p.position();
			Entity c = t.chargeId < 0 ? null : p.level().getEntity(t.chargeId);
			t.chargeAliveAtPearlEnd = c != null && c.isAlive();
			if(c != null && t.pearlLastPos != null)
				t.chargeDistAtPearlEnd =
					c.position().distanceTo(t.pearlLastPos);
			if(t.pearlLastPos != null)
			{
				Vec3 to = t.pearlLastPos.add(t.pearlLastVel.scale(3));
				t.pearlHitBlockAhead = p.level()
					.clip(new ClipContext(t.pearlLastPos, to,
						ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
					.getType() != HitResult.Type.MISS;
			}
		}else if(t.pearlGonePoll >= 0 && t.playerAfterPearl == null
			&& t.polls > t.pearlGonePoll)
			t.playerAfterPearl = p.position();
	}
	
	/** What's around the player and the planned catch at the pearl click. */
	private static JsonObject environment(Minecraft mc, Plan plan,
		SelfState self)
	{
		Level level = mc.level;
		JsonObject o = new JsonObject();
		o.addProperty("inWater", mc.player.isInWater());
		o.addProperty("floorBlock",
			level.getBlockState(BlockPos.containing(self.pos()).below())
				.getBlock().toString());
		
		Vec3 eye = self.eyePos();
		int ceiling = -1;
		for(int dy = 1; dy <= 64; dy++)
			if(!level.getBlockState(BlockPos.containing(eye.add(0, dy, 0)))
				.getCollisionShape(level,
					BlockPos.containing(eye.add(0, dy, 0)))
				.isEmpty())
			{
				ceiling = dy;
				break;
			}
		o.addProperty("ceilingAbove", ceiling);
		
		Vec3 dest = plan.destination();
		o.addProperty("pathBlocked",
			level
				.clip(new ClipContext(eye, dest, ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE, mc.player))
				.getType() != HitResult.Type.MISS);
		int solid = 0;
		BlockPos center = BlockPos.containing(dest);
		for(BlockPos pos : BlockPos.betweenClosed(center.offset(-2, -2, -2),
			center.offset(2, 2, 2)))
			if(!level.getBlockState(pos).getCollisionShape(level, pos)
				.isEmpty())
				solid++;
		o.addProperty("solidNearDestination", solid);
		return o;
	}
	
	private static List<JsonObject> rows(Scenario sc, List<Result> results,
		List<Trace> traces)
	{
		ArrayList<JsonObject> rows = new ArrayList<>();
		int catchIndex = 0;
		for(int i = 0; i < results.size(); i++)
		{
			Result r = results.get(i);
			if(!r.name().startsWith("PearlCatch"))
				continue;
			
			Trace t = null;
			for(Trace candidate : traces)
				if(candidate.resultIndex == i)
					t = candidate;
				
			JsonObject o = new JsonObject();
			o.addProperty("catchIndex", catchIndex++);
			o.addProperty("action", r.name());
			o.addProperty("status", r.status().name());
			o.addProperty("reason", r.reason());
			o.addProperty("ticks", r.endTick() - r.startTick());
			o.add("startPos", vec(r.startPos()));
			o.add("endPos", vec(r.endPos()));
			
			Prediction pr = r.prediction();
			if(pr != null)
			{
				JsonObject p = new JsonObject();
				p.addProperty("feasible", pr.feasible());
				p.addProperty("reason", pr.reason());
				p.addProperty("pSuccess", pr.pSuccess());
				p.add("endPos", vec(pr.feasible() ? pr.endPos() : null));
				p.addProperty("ticks", pr.ticks());
				o.add("prediction", p);
			}
			
			Caught caught = r.payload(Caught.class);
			o.addProperty("pearlThrown", t != null);
			o.addProperty("chargeThrown", t != null && t.atThrow != null);
			o.addProperty("caught",
				r.status() == Status.DONE && caught != null);
			if(caught != null)
			{
				o.add("teleportedTo", vec(caught.teleportedTo()));
				o.addProperty("destinationError", caught.teleportedTo()
					.distanceTo(caught.plan().destination()));
			}
			
			if(t != null)
			{
				o.add("click", inFlight(t.atClick));
				o.add("clickEnv", t.clickEnv);
				if(t.atThrow != null)
					o.add("throw", inFlight(t.atThrow));
				o.add("server", server(t));
			}
			rows.add(o);
		}
		
		if(rows.isEmpty())
		{
			// the catch never started, e.g. its start condition never came
			JsonObject o = new JsonObject();
			o.addProperty("status", "NOT_STARTED");
			o.addProperty("caught", false);
			o.addProperty("pearlThrown", false);
			JsonArray names = new JsonArray();
			for(Result r : results)
				names.add(r.toString());
			o.add("results", names);
			rows.add(o);
		}
		return rows;
	}
	
	private static JsonObject inFlight(InFlight f)
	{
		JsonObject o = new JsonObject();
		o.addProperty("tick", f.last().tick());
		o.addProperty("delay", f.delay());
		o.addProperty("chance", f.chance());
		o.addProperty("pearlObserved", f.info().observed());
		o.add("pearlKnownMovement", vec(f.info().knownMovement()));
		o.add("plan", plan(f.plan(), f.clickTick()));
		o.add("self", self(f.last()));
		return o;
	}
	
	private static JsonObject plan(Plan plan, int clickTick)
	{
		JsonObject o = new JsonObject();
		o.addProperty("throwAfter", plan.throwTick() - clickTick);
		o.addProperty("aimYaw", plan.aim().yaw());
		o.addProperty("aimPitch", plan.aim().pitch());
		o.addProperty("catchMove", plan.catchMove());
		o.add("destination", vec(plan.destination()));
		o.addProperty("pSuccess", plan.pSuccess());
		return o;
	}
	
	private static JsonObject self(SelfState s)
	{
		JsonObject o = new JsonObject();
		o.add("pos", vec(s.pos()));
		o.add("vel", vec(s.vel()));
		o.addProperty("onGround", s.onGround());
		o.addProperty("fallDistance", s.fallDistance());
		o.addProperty("yaw", s.yaw());
		o.addProperty("pitch", s.pitch());
		o.addProperty("moveLeft", s.move().left());
		o.addProperty("moveForward", s.move().forward());
		o.addProperty("sprinting", s.sprinting());
		o.addProperty("jump", s.jump());
		o.addProperty("selected", s.kit().selected());
		o.addProperty("pendingCount", s.pending().size());
		return o;
	}
	
	private static JsonObject server(Trace t)
	{
		JsonObject o = new JsonObject();
		o.addProperty("pearlSeen", t.pearlId >= 0);
		o.addProperty("chargeSeen", t.chargeId >= 0);
		o.addProperty("pearlFirstPoll", t.pearlFirstPoll);
		o.addProperty("chargeFirstPoll", t.chargeFirstPoll);
		o.addProperty("gap",
			t.serverGap == Integer.MIN_VALUE ? null : t.serverGap);
		o.addProperty("minPearlChargeDist",
			Double.isInfinite(t.minDist) ? null : t.minDist);
		o.add("pearlLastPos", vec(t.pearlLastPos));
		o.add("pearlLastVel", vec(t.pearlLastVel));
		o.addProperty("pearlLastAge", t.pearlLastAge);
		o.addProperty("pearlGonePoll", t.pearlGonePoll);
		o.addProperty("chargeAliveAtPearlEnd", t.chargeAliveAtPearlEnd);
		o.addProperty("chargeDistAtPearlEnd",
			Double.isNaN(t.chargeDistAtPearlEnd) ? null
				: t.chargeDistAtPearlEnd);
		o.addProperty("pearlHitBlockAhead", t.pearlHitBlockAhead);
		o.add("playerAtPearlEnd", vec(t.playerAtPearlEnd));
		o.add("playerAfterPearl", vec(t.playerAfterPearl));
		return o;
	}
	
	private static JsonArray vec(Vec3 v)
	{
		if(v == null)
			return null;
		JsonArray a = new JsonArray();
		a.add(v.x);
		a.add(v.y);
		a.add(v.z);
		return a;
	}
	
	/**
	 * Waits until the pearl and the wind charge are ready and
	 * {@code condition} holds, then {@code extra} more updates.
	 */
	private static StartCondition ready(StartCondition condition, int extra)
	{
		int[] waited = {-1};
		return runner -> {
			if(waited[0] < 0)
			{
				// the condition sees every update, even during cooldowns
				boolean holds = condition.test(runner);
				Kit kit = Kit.capture();
				if(kit.enderPearl() < 0 || kit.windCharge() < 0 || !holds)
					return false;
				waited[0] = 0;
			}
			return waited[0]++ >= extra;
		};
	}
	
	/**
	 * In the air with a vertical velocity of at most {@code vy}, or on the
	 * ground for a few updates, e.g. after a hop under a low ceiling.
	 */
	private static StartCondition airborneAt(double vy)
	{
		int[] groundTicks = {0};
		return StartCondition.when(p -> {
			groundTicks[0] = p.onGround() ? groundTicks[0] + 1 : 0;
			return groundTicks[0] >= 5
				|| !p.onGround() && p.getDeltaMovement().y <= vy;
		});
	}
	
	/** Sets the player's velocity on the server, like a knockback. */
	private void push(Vec3 vel)
	{
		server.runOnServer(mc -> {
			ServerPlayer p = mc.getPlayerList().getPlayers().getFirst();
			p.setDeltaMovement(vel);
			p.connection.send(new ClientboundSetEntityMotionPacket(p));
		});
	}
	
	private void buildEnvironment(Scenario sc)
	{
		if(!sc.floor().equals("smooth_stone"))
			fill(-FLOOR_RADIUS, FLOOR_Y, -FLOOR_RADIUS, FLOOR_RADIUS, FLOOR_Y,
				FLOOR_RADIUS, sc.floor(), "smooth_stone");
		
		int ground = (int)GROUND_Y;
		int size = sc.envSize();
		int h = sc.envHeight();
		Random r = new Random(sc.seed() * 31 + 7);
		switch(sc.env())
		{
			case CEILING -> fill(-8, ground + h, -8, 8, ground + h, 8, "glass",
				"air");
			case WALL ->
			{
				// across the catch direction, size blocks ahead
				double rad = Math.toRadians(sc.yaw());
				int cx = (int)Math.floor(0.5 - Math.sin(rad) * size);
				int cz = (int)Math.floor(0.5 + Math.cos(rad) * size);
				boolean alongX =
					Math.abs(Math.cos(rad)) > Math.abs(Math.sin(rad));
				if(alongX)
					fill(cx - 6, ground, cz, cx + 6, ground + h, cz, "stone",
						"air");
				else
					fill(cx, ground, cz - 6, cx, ground + h, cz + 6, "stone",
						"air");
			}
			case PILLARS ->
			{
				int n = 2 + r.nextInt(7);
				for(int i = 0; i < n; i++)
				{
					double angle = r.nextDouble() * Math.PI * 2;
					double dist = 2 + r.nextDouble() * 9;
					int x = (int)Math.floor(0.5 + Math.cos(angle) * dist);
					int z = (int)Math.floor(0.5 + Math.sin(angle) * dist);
					fill(x, ground, z, x, ground + 2 + r.nextInt(20), z,
						"stone", "air");
				}
			}
			case ROOM ->
			{
				int half = Math.max(2, size / 2 + 1);
				int top = ground + Math.min(h, 4 + r.nextInt(10));
				fill(-half, ground, -half, half, top, -half, "glass", "air");
				fill(-half, ground, half, half, top, half, "glass", "air");
				fill(-half, ground, -half, -half, top, half, "glass", "air");
				fill(half, ground, -half, half, top, half, "glass", "air");
				fill(-half, top, -half, half, top, half, "glass", "air");
			}
			default ->
				{
				}
		}
		
		if(sc.kind() == Kind.PILLAR_TOP)
		{
			int w = sc.extraTicks() % 2;
			fill(-w, ground, -w, w, ground + (int)sc.height() - 1, w,
				"smooth_stone", "air");
		}
		if(sc.kind() == Kind.WATER)
		{
			int d = sc.waterDepth();
			fill(-4, ground, -4, 4, ground + d - 1, 4, "glass", "air");
			fill(-3, ground, -3, 3, ground + d - 1, 3, "water", "air");
		}
	}
	
	private void fill(int x1, int y1, int z1, int x2, int y2, int z2,
		String block, String restore)
	{
		BlockPos from = new BlockPos(x1, y1, z1);
		BlockPos to = new BlockPos(x2, y2, z2);
		built.add(new Box(from, to, restore));
		runCommand(server, String.format("fill %d %d %d %d %d %d %s", x1, y1,
			z1, x2, y2, z2, block));
	}
	
	private void cleanUp()
	{
		context.runOnClient(mc -> {
			holdKeys(mc, "");
			IKeyMapping.get(mc.options.keyShift).resetDownState();
			IKeyMapping.get(mc.options.keyJump).resetDownState();
			if(mc.player.isDeadOrDying())
				mc.player.respawn();
		});
		
		// water first, so it can't flow into the gaps of removed glass
		for(Box b : built.reversed())
			runCommand(server,
				String.format("fill %d %d %d %d %d %d %s", b.from().getX(),
					b.from().getY(), b.from().getZ(), b.to().getX(),
					b.to().getY(), b.to().getZ(), b.restore()));
		built.clear();
		runCommand(server, "kill @e[type=!player]");
	}
	
	/**
	 * Holds W, A, S, D, sprint ("R"), sneak ("N") and jump ("J") as listed in
	 * {@code keys}, releasing the others.
	 */
	private static void holdKeys(Minecraft mc, String keys)
	{
		mc.options.keyUp.setDown(keys.contains("W"));
		mc.options.keyLeft.setDown(keys.contains("A"));
		mc.options.keyDown.setDown(keys.contains("S"));
		mc.options.keyRight.setDown(keys.contains("D"));
		mc.options.keySprint.setDown(keys.contains("R"));
		mc.options.keyShift.setDown(keys.contains("N"));
		mc.options.keyJump.setDown(keys.contains("J"));
	}
}
