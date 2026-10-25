/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import static net.wurstclient.gametest.WurstClientTestHelper.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.SpearMaceHack;
import net.wurstclient.hacks.spearmace.action.ActionRunner;
import net.wurstclient.hacks.spearmace.action.ActionRunner.Result;
import net.wurstclient.hacks.spearmace.action.ActionRunner.StartCondition;
import net.wurstclient.hacks.spearmace.action.ActionRunner.Status;
import net.wurstclient.hacks.spearmace.combat.Engage;
import net.wurstclient.hacks.spearmace.combat.Engage.Engaged;
import net.wurstclient.hacks.spearmace.combat.Engagement;
import net.wurstclient.hacks.spearmace.combat.Loadout;
import net.wurstclient.hacks.spearmace.combat.Loadout.PreItem;
import net.wurstclient.hacks.spearmace.combat.MaceStrike;
import net.wurstclient.hacks.spearmace.combat.MaceStrike.Struck;
import net.wurstclient.hacks.spearmace.combat.StrikeOption;
import net.wurstclient.hacks.spearmace.combat.StrikeSelector.Kind;
import net.wurstclient.hacks.spearmace.combat.StunSlam.Slammed;
import net.wurstclient.hacks.spearmace.input.KeyInput;
import net.wurstclient.hacks.spearmace.maneuver.PearlCatch;
import net.wurstclient.hacks.spearmace.maneuver.PearlCatch.Caught;
import net.wurstclient.hacks.spearmace.maneuver.SpearLunge;
import net.wurstclient.hacks.spearmace.maneuver.SpearLunge.Lunged;
import net.wurstclient.hacks.spearmace.maneuver.WindChargeJump;
import net.wurstclient.hacks.spearmace.model.Recorder;
import net.wurstclient.hacks.spearmace.model.Recorder.Frame;
import net.wurstclient.hacks.spearmace.model.Replay;
import net.wurstclient.hacks.spearmace.model.Replay.Report;
import net.wurstclient.hacks.spearmace.model.Replay.StepError;
import net.wurstclient.hacks.spearmace.model.TargetModel;
import net.wurstclient.hacks.spearmace.model.TickSim;
import net.wurstclient.hacks.spearmace.plan.DefaultController;
import net.wurstclient.hacks.spearmace.plan.Options;
import net.wurstclient.hacks.spearmace.plan.Planner;
import net.wurstclient.hacks.spearmace.plan.Runner;
import net.wurstclient.hacks.spearmace.plan.Runner.Entry;
import net.wurstclient.hacks.spearmace.plan.Search;
import net.wurstclient.hacks.spearmace.plan.eval.DamageRace;
import net.wurstclient.hacks.spearmace.plan.search.BeamSearch;
import net.wurstclient.hacks.spearmace.plan.search.Mcts;
import net.wurstclient.hacks.spearmace.plan.search.RuleSearch;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.sim.TargetSim;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.state.Pending.PearlInFlight;
import net.wurstclient.hacks.spearmace.state.Pending.WindChargeInFlight;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.util.Rotation;

/**
 * Scenario tests for the SpearMace actions. Only runs when the
 * {@code WURST_SPEARMACE_TEST} environment variable is set, in which case
 * the regular {@link WurstTest} suite is skipped:
 *
 * <pre>
 * WURST_SPEARMACE_TEST=1 ./gradlew runClientGameTestWithMods
 * </pre>
 *
 * Set it to {@code movement} or {@code combat} to run only that group.
 * The far-away target scenarios are harder and only run when it is set to
 * {@code hard}, and the pearl catches with movement keys held only when it
 * is set to {@code keys} ({@code all} runs everything).
 *
 * Every tick of every scenario is logged with the predicted and actual
 * state, so the logs double as calibration data for the forward models.
 * SpearMacePreview is enabled, so the predictions can be watched while the
 * tests run, and the combat scenarios are shown in third person.
 */
public final class SpearMaceTest implements FabricClientGameTest
{
	private static final Logger LOGGER = WurstTest.LOGGER;
	
	/** Hotbar layout set up by {@link #reset}. */
	private static final int HAND_SLOT = 0;
	private static final int SPEAR_SLOT = 1;
	
	/** Where combat targets stand. */
	private static final double GROUND_Y = -60;
	private static final double TARGET_X = 0.5;
	private static final double TARGET_Z = 6.5;
	private static final double TARGET_HEALTH = 1000;
	
	/** Where the far-away targets start, straight ahead. */
	private static final double FAR_Z = 40.5;
	private static final double FAR_SPEED = 0.1;
	private static final int FAR_TICKS = 600;
	private static final int BOUNCE_COOLDOWN = 10;
	
	private static final Rotation UP = new Rotation(0, -90);
	private static final Rotation DIAGONAL = new Rotation(0, -30);
	private static final Rotation STEEP_DIAGONAL = new Rotation(0, -45);
	
	private ActionRunner runner;
	private List<Frame> lastFrames = List.of();
	
	private record NamedReport(String name, Report filtered, Report open)
	{}
	
	private final ArrayList<NamedReport> replays = new ArrayList<>();
	private TestServerContext server;
	
	/**
	 * Server ticks between the pearl and the wind charge spawning, as seen
	 * by {@link #logServerProjectiles}, or -1.
	 */
	private volatile int serverGap = -1;
	
	/** The combat target whose server-side health gets logged, or -1. */
	private int targetId = -1;
	private double initialHealth;
	private double targetHealth;
	private ShieldDummy shieldDummy;
	
	private static final List<String> OPT_IN_GROUPS = List.of("hard", "keys");
	
	public static boolean isEnabled()
	{
		return System.getenv("WURST_SPEARMACE_TEST") != null;
	}
	
	/**
	 * The {@code hard} and {@code keys} groups only run when asked for by
	 * name or with {@code all}, the other groups also run for any other
	 * value.
	 */
	private static boolean runs(String group)
	{
		String value = System.getenv("WURST_SPEARMACE_TEST");
		if(OPT_IN_GROUPS.contains(group))
			return group.equals(value) || "all".equals(value);
		return !List.of("movement", "combat").contains(value)
			&& !OPT_IN_GROUPS.contains(value) || group.equals(value);
	}
	
	@Override
	public void runTest(ClientGameTestContext context)
	{
		if(!isEnabled())
			return;
		
		hideSplashTexts(context);
		waitForTitleScreenFade(context);
		
		TestWorldBuilder worldBuilder = context.worldBuilder();
		worldBuilder.adjustSettings(creator -> {
			creator.setName("SpearMace Test");
			creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			creator.getGameRules().set(GameRules.SEND_COMMAND_FEEDBACK, false,
				null);
			WurstTest.applyFlatPresetWithSmoothStone(creator);
		});
		
		ArrayList<String> failures = new ArrayList<>();
		try(TestSingleplayerContext spContext = worldBuilder.create())
		{
			TestServerContext server = spContext.getServer();
			this.server = server;
			runCommand(server, "time set noon");
			runCommand(server, "difficulty peaceful");
			runCommand(server, "gamerule spawn_mobs false");
			context.waitTicks(2);
			spContext.getConnection().waitForChunksRender();
			
			// draw the forward model in every scenario
			context.runOnClient(
				mc -> WurstClient.INSTANCE.getHax().spearMacePreviewHack
					.setEnabled(true));
			
			testStateCapture(context, server, failures);
			testPreview(context, server);
			
			if(runs("movement"))
			{
				testWindChargeStanding(context, server, failures, false);
				testWindChargeStanding(context, server, failures, true);
				testWindChargeMoving(context, server, failures);
				testLungeAngles(context, server, failures);
				testVerticalPearlCatch(context, server, failures);
				testDiagonalCatchAfterWindCharge(context, server, failures);
				testLungesWhileFalling(context, server, failures);
				testChain(context, server, failures);
				testInfeasibleCatch(context, server, failures);
			}
			
			// watch the combat scenarios from behind
			if(runs("combat"))
			{
				setCamera(context, CameraType.THIRD_PERSON_BACK);
				runCombatTests(context, server, failures);
				setCamera(context, CameraType.FIRST_PERSON);
			}
			
			if(runs("hard"))
			{
				setCamera(context, CameraType.THIRD_PERSON_BACK);
				runHardTests(context, server, failures);
				setCamera(context, CameraType.FIRST_PERSON);
			}
			
			if(runs("keys"))
				testCatchesHoldingKeys(context, server, failures);
		}
		
		for(NamedReport r : replays)
		{
			LOGGER.info("SM replay summary [{}] {}", r.name(),
				r.filtered().summary());
			checkReplay(r, failures);
		}
		
		if(!failures.isEmpty())
			throw new AssertionError(
				"SpearMace tests failed:\n" + String.join("\n", failures));
		
		LOGGER.info("SpearMace tests passed");
	}
	
	/**
	 * Checks that {@link GameState#capture} matches the live game, survives
	 * inventory changes, sees the player's projectiles and cooldowns, and
	 * that the sims predict the same from a state as from the live player.
	 */
	private void testStateCapture(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		String name = "State capture";
		reset(context, server);
		int golem = spawnGolem(server, TARGET_X, GROUND_Y, TARGET_Z, true);
		context.waitFor(mc -> clientEntity(golem) != null);
		
		context.runOnClient(mc -> {
			GameState state = GameState.capture(clientEntity(golem));
			SelfState self = state.self();
			LOGGER.info("SM: {} standing {}", name, state);
			check(failures, name, "pos",
				self.pos().equals(mc.player.position()));
			check(failures, name, "onGround", self.onGround());
			check(failures, name, "selected",
				self.kit().selected() == HAND_SLOT);
			check(failures, name, "mace slot", self.kit().mace() == 4);
			check(failures, name, "spear slot",
				self.kit().spear() == SPEAR_SLOT);
			check(failures, name, "wind charge slot",
				self.kit().windCharge() == 2);
			check(failures, name, "attack delay",
				Math.abs(self.kit().attackDelay(self.kit().held())
					- mc.player.getCurrentItemAttackStrengthDelay()) < 1e-6);
			check(failures, name, "target pos",
				state.target().pos().equals(clientEntity(golem).position()));
			check(failures, name, "target box", state.target().box()
				.equals(clientEntity(golem).getBoundingBox()));
			check(failures, name, "no projectiles", self.pending().isEmpty());
		});
		
		// the snapshot keeps its items when the inventory changes
		GameState[] before = new GameState[1];
		context.runOnClient(
			mc -> before[0] = GameState.capture(clientEntity(golem)));
		runCommand(server, "clear");
		context.waitTicks(2);
		context.runOnClient(mc -> {
			Kit kit = before[0].self().kit();
			check(failures, name, "snapshot mace", kit.mace() == 4
				&& kit.get(4).is(Items.MACE) && Loadout.mace() < 0);
		});
		
		// a wind charge thrown ahead, then a pearl straight up
		reset(context, server);
		context.runOnClient(mc -> {
			KeyInput.look(new Rotation(0, 0));
			KeyInput.selectSlot(2);
			KeyInput.clickUse();
		});
		context.waitTicks(3);
		context.runOnClient(mc -> {
			KeyInput.look(UP);
			KeyInput.selectSlot(3);
			KeyInput.clickUse();
		});
		context.waitTicks(PearlCatch.SEEN_DELAY + 1);
		context.runOnClient(mc -> {
			SelfState self = SelfState.capture();
			LOGGER.info("SM: {} after throws {}", name, self);
			Kit kit = self.kit();
			ItemStack charge = mc.player.getInventory().getItem(2);
			ItemStack pearl = mc.player.getInventory().getItem(3);
			check(failures, name, "charge cooldown " + kit.cooldown(charge),
				kit.cooldown(charge) > 0 && kit.cooldown(charge) < 10
					&& mc.player.getCooldowns().isOnCooldown(charge));
			check(failures, name, "pearl cooldown " + kit.cooldown(pearl),
				kit.cooldown(pearl) > 10 && kit.cooldown(pearl) < 20);
			check(failures, name, "charge not ready", kit.windCharge() < 0);
			check(failures, name, "wind charge in flight",
				self.pending(WindChargeInFlight.class).size() == 1);
			check(failures, name, "pearl in flight",
				self.pending(PearlInFlight.class).size() == 1);
		});
		
		// sims started from a state match sims started from the live game
		reset(context, server);
		int golem2 =
			spawnGolem(server, TARGET_X, GROUND_Y + 5, TARGET_Z, false);
		runCommand(server, "tp @p " + TARGET_X + " " + (GROUND_Y + 10) + " "
			+ (TARGET_Z - 2) + " 0 90");
		context.waitFor(
			mc -> !mc.player.onGround() && clientEntity(golem2) != null);
		context.runOnClient(mc -> {
			GameState state = GameState.capture(clientEntity(golem2));
			PlayerSim fromState = PlayerSim.of(mc.level, state.self());
			PlayerSim live = PlayerSim.ofLocalPlayer();
			TargetSim targetFromState = TargetSim.of(mc.level, state.target());
			TargetSim targetLive = TargetSim.of(clientEntity(golem2));
			for(int i = 0; i < 30; i++)
			{
				fromState.step();
				live.step();
				targetFromState.step();
				targetLive.step();
			}
			check(failures, name, "player sim", fromState.pos.equals(live.pos));
			check(failures, name, "target sim",
				targetFromState.pos.equals(targetLive.pos));
			Engagement e = Engagement.of(mc.level, state);
			check(failures, name, "engagement landing " + e.landing(),
				e.landing() > 0 && e.landing() <= Engagement.HORIZON);
		});
	}
	
	/**
	 * Takes screenshots of SpearMacePreview, which stays enabled for all
	 * scenarios, while falling toward a target and while a pearl flies.
	 */
	private void testPreview(ClientGameTestContext context,
		TestServerContext server)
	{
		reset(context, server);
		int golem = spawnGolem(server, TARGET_X, GROUND_Y, TARGET_Z, true);
		runCommand(server, "tp @p " + TARGET_X + " " + (GROUND_Y + 14) + " "
			+ (TARGET_Z - 8) + " 0 65");
		context.waitFor(
			mc -> !mc.player.onGround() && clientEntity(golem) != null);
		context.waitTicks(4);
		context.takeScreenshot("spearmace_preview_fall");
		
		reset(context, server);
		context.runOnClient(mc -> {
			KeyInput.look(new Rotation(0, -30));
			KeyInput.selectSlot(3);
			KeyInput.clickUse();
		});
		context.waitTicks(8);
		context.takeScreenshot("spearmace_preview_pearl");
	}
	
	private static void check(List<String> failures, String name, String what,
		boolean ok)
	{
		if(!ok)
			failures.add(name + ": " + what);
	}
	
	private void testWindChargeStanding(ClientGameTestContext context,
		TestServerContext server, List<String> failures, boolean jump)
	{
		String name = "WindCharge standing" + (jump ? " + jump" : "");
		reset(context, server);
		
		Vec3 start = playerPos(context);
		List<Result> results =
			run(context, name, 60, r -> r.enqueue(WindChargeJump.atFeet(jump)));
		
		Result r = single(results, name, failures);
		if(r == null)
			return;
		
		double predicted = r.prediction().endPos().y - start.y;
		double actual = r.endPos().y - start.y;
		LOGGER.info("SM: {} apex gain predicted={} actual={}", name,
			fmt(predicted), fmt(actual));
		if(actual < 2)
			failures.add(name + ": barely launched, gain=" + fmt(actual));
		if(Math.abs(actual - predicted) > 0.3)
			failures.add(name + ": apex gain " + fmt(actual) + " vs predicted "
				+ fmt(predicted));
	}
	
	private void testWindChargeMoving(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		String name = "WindCharge sprinting";
		reset(context, server);
		
		context.runOnClient(mc -> {
			mc.options.keyUp.setDown(true);
			mc.options.keySprint.setDown(true);
		});
		context.waitTicks(15);
		
		Vec3 start = playerPos(context);
		List<Result> results = run(context, name, 60,
			r -> r.enqueue(WindChargeJump.atFeet(false)));
		releaseKeys(context);
		
		Result r = single(results, name, failures);
		if(r == null)
			return;
		
		Vec3 predicted = r.prediction().endPos();
		Vec3 actual = r.endPos();
		LOGGER.info("SM: {} start={} predicted={} actual={}", name, fmt(start),
			fmt(predicted), fmt(actual));
		if(actual.y - start.y < 2)
			failures.add(name + ": barely launched");
		if(Math.abs(actual.y - predicted.y) > 0.4)
			failures.add(name + ": apex y " + fmt(actual.y) + " vs predicted "
				+ fmt(predicted.y));
		if(horizontalDist(actual, predicted) > 0.6)
			failures.add(name + ": apex " + fmt(actual) + " vs predicted "
				+ fmt(predicted));
	}
	
	private void testLungeAngles(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		double lastDistance = Double.MAX_VALUE;
		for(float pitch : new float[]{0, 30, 60})
		{
			String name = "Lunge pitch " + pitch;
			reset(context, server);
			
			Vec3 start = playerPos(context);
			int[] spearTicks = {0};
			List<Result> results = run(context, name, 40,
				r -> r.enqueue(new SpearLunge(0, pitch)), mc -> {
					if(mc.player.getInventory().getSelectedSlot() == SPEAR_SLOT)
						spearTicks[0]++;
				});
			Result r = single(results, name, failures);
			if(r == null)
				continue;
			
			// let the player slide to a stop
			context.waitTicks(20);
			double distance = horizontalDist(playerPos(context), start);
			int slot = context.computeOnClient(
				mc -> mc.player.getInventory().getSelectedSlot());
			
			Lunged lunge = r.payload(Lunged.class);
			Vec3 expected = lunge.expected();
			Vec3 measured = lunge.measured();
			LOGGER.info(
				"SM: {} impulse expected={} measured={} distance={} spearTicks={}",
				name, fmt(expected), fmt(measured), fmt(distance),
				spearTicks[0]);
			
			if(measured.distanceTo(expected) > 0.15 * expected.length())
				failures.add(name + ": impulse " + fmt(measured)
					+ " vs expected " + fmt(expected));
			if(distance >= lastDistance)
				failures.add(name + ": went " + fmt(distance)
					+ " blocks, not less than the flatter lunge");
			if(slot != HAND_SLOT)
				failures.add(name + ": still holding slot " + slot);
			if(spearTicks[0] > 2)
				failures.add(
					name + ": held the spear for " + spearTicks[0] + " ticks");
			lastDistance = distance;
		}
	}
	
	private void testVerticalPearlCatch(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		double shortY = pearlCatchTrials(context, server, failures,
			"Vertical catch delay 2", UP, 2, 5, 4);
		double longY = pearlCatchTrials(context, server, failures,
			"Vertical catch delay 4", UP, 4, 5, 4);
		if(!(longY > shortY))
			failures.add("Vertical catch: later catch wasn't higher (delay 2: "
				+ fmt(shortY) + ", delay 4: " + fmt(longY) + ")");
	}
	
	/**
	 * @return the average height gained by the successful catches
	 */
	private double pearlCatchTrials(ClientGameTestContext context,
		TestServerContext server, List<String> failures, String name,
		Rotation aim, int delay, int trials, int required)
	{
		int successes = 0;
		double gained = 0;
		for(int i = 0; i < trials; i++)
		{
			String trial = name + " #" + (i + 1);
			reset(context, server);
			Vec3 start = playerPos(context);
			Result r = single(run(context, trial, 120,
				rr -> rr.enqueue(new PearlCatch(aim, delay))), trial, null);
			if(r == null)
				continue;
			
			Caught pearlCatch = r.payload(Caught.class);
			double error =
				pearlCatch.teleportedTo().distanceTo(r.prediction().endPos());
			LOGGER.info("SM: {} teleported to {} predicted {} error={}", trial,
				fmt(pearlCatch.teleportedTo()), fmt(r.prediction().endPos()),
				fmt(error));
			// within about one pearl move
			if(error > 1.5)
				continue;
			
			successes++;
			gained += pearlCatch.teleportedTo().y - start.y;
		}
		
		LOGGER.info("SM: {} {}/{} accurate catches", name, successes, trials);
		if(successes < required)
			failures.add(name + ": only " + successes + "/" + trials
				+ " accurate catches");
		return successes == 0 ? Double.NaN : gained / successes;
	}
	
	private void testDiagonalCatchAfterWindCharge(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		int successes = 0;
		for(int i = 0; i < 5; i++)
		{
			String name = "WindCharge -> diagonal catch #" + (i + 1);
			reset(context, server);
			List<Result> results = run(context, name, 150, r -> {
				r.enqueue(WindChargeJump.atFeet(false));
				r.enqueue(PearlCatch.mostReliable(DIAGONAL),
					StartCondition.atApex());
			});
			
			if(!allDone(results, 2, name, null))
				continue;
			
			Result c = results.get(1);
			Caught pearlCatch = c.payload(Caught.class);
			double error =
				pearlCatch.teleportedTo().distanceTo(c.prediction().endPos());
			LOGGER.info(
				"SM: {} delay={} p={} from {} to {} predicted {} error={}",
				name, pearlCatch.delay(), fmt(c.prediction().pSuccess()),
				fmt(c.startPos()), fmt(pearlCatch.teleportedTo()),
				fmt(c.prediction().endPos()), fmt(error));
			// within about one pearl move
			if(error <= 1.5)
				successes++;
		}
		
		if(successes < 3)
			failures.add("WindCharge -> diagonal catch: only " + successes
				+ "/5 accurate catches");
	}
	
	private void testLungesWhileFalling(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		String name = "Lunges while falling";
		reset(context, server);
		runCommand(server, "tp @p ~ ~40 ~ 0 0");
		context.waitFor(mc -> !mc.player.onGround());
		
		Vec3 start = playerPos(context);
		List<Result> results = run(context, name, 200, r -> {
			for(int i = 0; i < 4; i++)
				r.enqueue(new SpearLunge(0, 0), StartCondition
					.when(p -> !p.onGround() && p.getY() > start.y - 35));
		});
		context.waitFor(mc -> mc.player.onGround(), 200);
		double distance = horizontalDist(playerPos(context), start);
		
		allDone(results, 4, name, failures);
		for(int i = 0; i < results.size(); i++)
		{
			Lunged lunge = results.get(i).payload(Lunged.class);
			if(lunge == null)
				continue;
			Vec3 measured = lunge.measured();
			LOGGER.info(
				"SM: {} #{} impulse expected={} measured={} attempts={}", name,
				i + 1, fmt(lunge.expected()), fmt(measured), lunge.attempts());
			if(measured.distanceTo(lunge.expected()) > 0.2
				* lunge.expected().length())
				failures.add(name + " #" + (i + 1) + ": impulse "
					+ fmt(measured) + " vs " + fmt(lunge.expected()));
		}
		
		LOGGER.info("SM: {} travelled {} blocks", name, fmt(distance));
		if(distance < 20)
			failures.add(name + ": only travelled " + fmt(distance));
	}
	
	private void testChain(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		int successes = 0;
		for(int i = 0; i < 5; i++)
		{
			String name = "Chain #" + (i + 1);
			reset(context, server);
			List<Result> results = run(context, name, 200, r -> {
				r.enqueue(WindChargeJump.atFeet(false));
				r.enqueue(new SpearLunge(0, 0), StartCondition.atApex());
				r.enqueue(PearlCatch.mostReliable(STEEP_DIAGONAL));
			});
			
			if(!allDone(results, 3, name, null))
				continue;
			
			Result c = results.get(2);
			Caught pearlCatch = c.payload(Caught.class);
			double error =
				pearlCatch.teleportedTo().distanceTo(c.prediction().endPos());
			LOGGER.info(
				"SM: {} catch delay={} p={} from {} to {} predicted {} error={}",
				name, pearlCatch.delay(), fmt(c.prediction().pSuccess()),
				fmt(c.startPos()), fmt(pearlCatch.teleportedTo()),
				fmt(c.prediction().endPos()), fmt(error));
			// after the lunge, one pearl move is about 2.6 blocks
			if(error <= 3)
				successes++;
		}
		
		if(successes < 3)
			failures.add("Chain: only " + successes + "/5 complete chains");
	}
	
	private void testInfeasibleCatch(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		String name = "Infeasible catch";
		reset(context, server);
		
		int maxDelay =
			context.computeOnClient(mc -> PearlCatch.maxFeasibleDelay(mc.level,
				SelfState.capture(), UP, PearlCatch.MIN_CHANCE));
		LOGGER.info("SM: {} max feasible delay straight up = {}", name,
			maxDelay);
		if(maxDelay < 2 || maxDelay >= PearlCatch.MAX_DELAY)
		{
			failures.add(name + ": implausible max feasible delay " + maxDelay);
			return;
		}
		
		int tooFar = maxDelay + 6;
		int pearlsBefore = countPearls(context);
		List<Result> results =
			run(context, name, 20, r -> r.enqueue(new PearlCatch(UP, tooFar)));
		int pearlsAfter = countPearls(context);
		
		Result r = results.isEmpty() ? null : results.get(0);
		if(r == null || r.status() != Status.FAILED
			|| !r.reason().startsWith("infeasible"))
			failures.add(name + ": delay " + tooFar + " wasn't refused: " + r);
		if(pearlsAfter != pearlsBefore)
			failures.add(name + ": threw a pearl anyway");
	}
	
	/**
	 * Diagonal pearl catches with movement keys held, so the pearl and the
	 * wind charge leave from different places: walking on the ground, and
	 * drifting after a wind charge launch. "R" is sprint.
	 */
	private void testCatchesHoldingKeys(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		int trials = 3;
		int successes = 0;
		int attempts = 0;
		
		// catching toward the walking direction, and 60 degrees off it
		List<String> groundKeys = List.of("W", "WR", "S", "A", "D", "WA", "SD");
		for(String keys : groundKeys)
			for(int i = 0; i < trials; i++)
			{
				attempts++;
				if(catchHoldingKeys(context, server, keys, DIAGONAL, false,
					i + 1))
					successes++;
			}
		for(int i = 0; i < trials; i++)
		{
			attempts++;
			if(catchHoldingKeys(context, server, "WR", new Rotation(60, -30),
				false, i + 1))
				successes++;
		}
		
		List<String> apexKeys = List.of("W", "S", "A", "D", "WA");
		for(String keys : apexKeys)
			for(int i = 0; i < trials; i++)
			{
				attempts++;
				if(catchHoldingKeys(context, server, keys, DIAGONAL, true,
					i + 1))
					successes++;
			}
		
		LOGGER.info("SM: Catches holding keys {}/{} accurate", successes,
			attempts);
		if(successes < attempts * 3 / 4)
			failures.add("Catches holding keys: only " + successes + "/"
				+ attempts + " accurate catches");
	}
	
	/**
	 * @param fromApex
	 *            launch with a wind charge first, press the keys once in
	 *            the air and catch at the apex; otherwise walk for a bit
	 *            and catch from the ground
	 * @return whether the catch landed where it was planned
	 */
	private boolean catchHoldingKeys(ClientGameTestContext context,
		TestServerContext server, String keys, Rotation aim, boolean fromApex,
		int trial)
	{
		String name = String.format("Keys %s %s catch yaw %.0f #%d", keys,
			fromApex ? "apex" : "ground", aim.yaw(), trial);
		reset(context, server);
		
		if(!fromApex)
		{
			context.runOnClient(mc -> holdKeys(mc, keys));
			context.waitTicks(10);
		}
		
		// the hack uses a fixed delay, see Options
		serverGap = -1;
		Vec3 start = playerPos(context);
		List<Result> results = run(context, name, 150, r -> {
			if(fromApex)
			{
				r.enqueue(WindChargeJump.atFeet(false));
				r.enqueue(new PearlCatch(aim, 3), StartCondition.atApex());
			}else
				r.enqueue(new PearlCatch(aim, 3));
		}, mc -> {
			if(fromApex && !mc.player.onGround())
				holdKeys(mc, keys);
		});
		releaseKeys(context);
		
		Result c = results.isEmpty() ? null : results.getLast();
		Caught caught = c == null ? null : c.payload(Caught.class);
		double error = caught == null ? Double.NaN
			: caught.teleportedTo().distanceTo(c.prediction().endPos());
		LOGGER.info(
			"SM: {} status={} reason={} delay={} p={} serverGap={} from {} to {} predicted {} error={}",
			name, c == null ? "none" : c.status(),
			c == null ? null : c.reason(),
			caught == null ? "?" : caught.delay(),
			c == null || c.prediction() == null ? "?"
				: fmt(c.prediction().pSuccess()),
			serverGap, fmt(c == null ? start : c.startPos()),
			fmt(caught == null ? null : caught.teleportedTo()),
			fmt(c == null || c.prediction() == null ? null
				: c.prediction().endPos()),
			fmt(error));
		return caught != null && error <= 1.5;
	}
	
	/** Holds W, A, S, D and sprint ("R") as listed in {@code keys}. */
	private static void holdKeys(Minecraft mc, String keys)
	{
		mc.options.keyUp.setDown(keys.contains("W"));
		mc.options.keyLeft.setDown(keys.contains("A"));
		mc.options.keyDown.setDown(keys.contains("S"));
		mc.options.keyRight.setDown(keys.contains("D"));
		mc.options.keySprint.setDown(keys.contains("R"));
	}
	
	private void runCombatTests(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		testStrikeFromAbove(context, server, failures, "Strike from high above",
			40, Kind.SPEARMACE);
		testStrikeFromAbove(context, server, failures,
			"Strike from medium height", 16, Kind.SWORDMACE);
		testStrikeFromAbove(context, server, failures, "Strike from low", 7,
			Kind.HANDMACE);
		testBelowReachable(context, server, failures);
		testBelowUnreachable(context, server, failures);
		testStunSlam(context, server, failures);
		testCombatChain(context, server, failures);
		testPlannerChain(context, server, failures);
		testSearchComparison(context, server, failures);
		testHack(context, server, failures);
	}
	
	private void testStrikeFromAbove(ClientGameTestContext context,
		TestServerContext server, List<String> failures, String name,
		double height, Kind expected)
	{
		reset(context, server);
		int golem = spawnGolem(server, TARGET_X, GROUND_Y, TARGET_Z, true);
		runCommand(server, "tp @p " + TARGET_X + " " + (GROUND_Y + height) + " "
			+ TARGET_Z + " 0 90");
		context.waitFor(
			mc -> !mc.player.onGround() && clientEntity(golem) != null);
		
		Engaged engage = runEngage(context, name, failures);
		if(engage == null)
			return;
		
		Kind kind = engage.decision().kind();
		if(kind != expected)
			failures.add(name + ": picked " + engage.decision() + ", expected "
				+ expected);
		if(engage.result() instanceof Struck strike)
			checkStrike(context, name, strike, kind == Kind.SPEARMACE,
				failures);
	}
	
	/**
	 * Checks damage and launch of a strike that just finished, then waits
	 * for the apex and compares it with the prediction.
	 */
	private void checkStrike(ClientGameTestContext context, String name,
		Struck strike, boolean beyondNormalReach, List<String> failures)
	{
		StrikeOption s = strike.option();
		double damage = initialHealth - targetHealth;
		LOGGER.info(
			"SM: {} struck {} damage actual={} predicted={} launch expected={} measured={} attempts={}",
			name, s, fmt(damage), fmt(s.damage()), fmt(strike.expectedLaunch()),
			fmt(strike.measuredLaunch()), strike.attempts());
		
		if(Math.abs(damage - s.damage()) > Math.max(1.5, 0.05 * s.damage()))
			failures.add(name + ": dealt " + fmt(damage) + " damage, predicted "
				+ fmt(s.damage()) + " (" + s + ")");
		if(Math.abs(strike.measuredLaunch() - strike.expectedLaunch()) > 0.15)
			failures.add(name + ": launched at " + fmt(strike.measuredLaunch())
				+ ", expected " + fmt(strike.expectedLaunch()));
		if(beyondNormalReach && !(s.distance() > Loadout.DEFAULT_REACH))
			failures.add(name + ": hit from " + fmt(s.distance())
				+ ", not beyond normal reach");
	}
	
	private void testBelowReachable(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		String name = "Below target, in spear reach";
		reset(context, server);
		
		// both fall from rest, but the client shows the golem a few ticks
		// late, so it seems to drift away while falling
		double feetY = GROUND_Y + 35;
		int golem = spawnGolemWithPlayer(server, feetY + 1.62 + 2, feetY);
		context.waitFor(
			mc -> !mc.player.onGround() && clientEntity(golem) != null);
		
		Engaged engage = runEngage(context, name, failures);
		if(engage == null)
			return;
		
		if(engage.decision().kind() != Kind.SPEARMACE)
		{
			failures.add(name + ": picked " + engage.decision());
			return;
		}
		
		Struck strike = (Struck)engage.result();
		checkStrike(context, name, strike, false, failures);
		
		context.waitFor(mc -> mc.player.getDeltaMovement().y <= 0, 100);
		double[] heights = context.computeOnClient(mc -> new double[]{
			mc.player.getY(), clientEntity(golem).getEyeY()});
		LOGGER.info("SM: {} at apex feet={} target eyes={}", name,
			fmt(heights[0]), fmt(heights[1]));
		if(!(heights[0] > heights[1]))
			failures.add(name + ": ended below the target (feet "
				+ fmt(heights[0]) + ", target eyes " + fmt(heights[1]) + ")");
	}
	
	private void testBelowUnreachable(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		String name = "Below target, out of reach";
		reset(context, server);
		
		double feetY = GROUND_Y + 35;
		int golem = spawnGolemWithPlayer(server, feetY + 1.62 + 9, feetY);
		context.waitFor(
			mc -> !mc.player.onGround() && clientEntity(golem) != null);
		
		double before = horizontalGap(context, golem);
		Engaged engage = runEngage(context, name, failures);
		if(engage == null)
			return;
		
		if(engage.decision().kind() != Kind.ESCAPE_LUNGE)
		{
			failures.add(name + ": picked " + engage.decision());
			return;
		}
		
		context.waitTicks(5);
		double after = horizontalGap(context, golem);
		Lunged lunge = (Lunged)engage.result();
		LOGGER.info("SM: {} gap {} -> {} impulse expected={} measured={}", name,
			fmt(before), fmt(after), fmt(lunge.expected()),
			fmt(lunge.measured()));
		if(after - before < 2)
			failures.add(name + ": only got " + fmt(after - before)
				+ " blocks further away");
	}
	
	private void testStunSlam(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		// control: a plain mace hit into the shield does nothing, which the
		// model knows, so the strike runs against its prediction
		String control = "Mace into raised shield";
		reset(context, server);
		int dummy = spawnShieldDummy(server);
		runCommand(server, "tp @p " + TARGET_X + " " + (GROUND_Y + 10) + " "
			+ (TARGET_Z - 1.2) + " 0 90");
		context.waitFor(
			mc -> !mc.player.onGround() && clientEntity(dummy) != null);
		context.waitTicks(1);
		
		List<Result> results = run(context, control, 80,
			r -> r.enqueueAnyway(new MaceStrike(PreItem.HAND)));
		long attacks =
			lastFrames.stream().filter(f -> f.input().attack()).count();
		double controlDamage = initialHealth - targetHealth;
		LOGGER.info("SM: {} {} attacks={} damage={}", control, results, attacks,
			fmt(controlDamage));
		if(attacks == 0)
			failures.add(control + ": never attacked");
		if(controlDamage > 0.5)
			failures.add(control + ": the shield let " + fmt(controlDamage)
				+ " damage through");
		
		String name = "StunSlam";
		reset(context, server);
		int dummy2 = spawnShieldDummy(server);
		runCommand(server, "tp @p " + TARGET_X + " " + (GROUND_Y + 10) + " "
			+ (TARGET_Z - 1.2) + " 0 90");
		context.waitFor(
			mc -> !mc.player.onGround() && clientEntity(dummy2) != null);
		context.waitTicks(1);
		
		Engaged engage = runEngage(context, name, failures);
		if(engage == null)
			return;
		
		if(engage.decision().kind() != Kind.STUNSLAM)
		{
			failures.add(name + ": picked " + engage.decision());
			return;
		}
		
		Slammed slam = (Slammed)engage.result();
		double damage = initialHealth - targetHealth;
		boolean[] shield = server.computeOnServer(mc -> {
			ShieldDummy d = shieldDummy;
			return new boolean[]{d.isUsingItem(),
				d.getCooldowns().isOnCooldown(new ItemStack(Items.SHIELD))};
		});
		LOGGER.info(
			"SM: {} damage actual={} predicted={} fall={} launch expected={} measured={} using={} cooldown={} clientSawDown={}",
			name, fmt(damage), fmt(slam.expectedDamage()),
			fmt(slam.expectedFall()), fmt(slam.expectedLaunch()),
			fmt(slam.measuredLaunch()), shield[0], shield[1],
			slam.shieldWasDown());
		
		if(shield[0] || !shield[1])
			failures.add(name + ": shield wasn't disabled (using=" + shield[0]
				+ ", cooldown=" + shield[1] + ")");
		if(Math.abs(damage - slam.expectedDamage()) > Math.max(1.5,
			0.05 * slam.expectedDamage()))
			failures.add(name + ": dealt " + fmt(damage) + " damage, predicted "
				+ fmt(slam.expectedDamage()));
		if(Math.abs(slam.measuredLaunch() - slam.expectedLaunch()) > 0.15)
			failures.add(name + ": launched at " + fmt(slam.measuredLaunch())
				+ ", expected " + fmt(slam.expectedLaunch()));
	}
	
	private void testCombatChain(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		String name = "WindCharge -> strike -> strike";
		reset(context, server);
		int golem = spawnGolem(server, TARGET_X, GROUND_Y, TARGET_Z, true);
		runCommand(server, "tp @p " + TARGET_X + " " + GROUND_Y + " "
			+ (TARGET_Z - 1.5) + " 0 0");
		context.waitFor(mc -> clientEntity(golem) != null);
		context.waitTicks(2);
		
		List<Result> results = run(context, name, 250, r -> {
			r.enqueue(WindChargeJump.atFeet(false));
			r.enqueue(new Engage(), StartCondition.atApex());
			r.enqueue(new Engage(), StartCondition.atApex());
		});
		
		if(!allDone(results, 3, name, failures))
			return;
		
		LOGGER.info("SM: {} picked {} then {}, total damage {}", name,
			results.get(1).payload(Engaged.class).decision(),
			results.get(2).payload(Engaged.class).decision(),
			fmt(initialHealth - targetHealth));
	}
	
	/**
	 * Same start as {@link #testCombatChain}, but nothing is queued: the
	 * planner has to find the launch, the strike, and the strike after
	 * the Wind Burst relaunch on its own.
	 */
	private void testPlannerChain(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		String name = "Planner chain";
		reset(context, server);
		int golem = spawnGolem(server, TARGET_X, GROUND_Y, TARGET_Z, true);
		runCommand(server, "tp @p " + TARGET_X + " " + GROUND_Y + " "
			+ (TARGET_Z - 1.5) + " 0 0");
		context.waitFor(mc -> clientEntity(golem) != null);
		context.waitTicks(2);
		
		List<Entry> history = runPlanner(context, name, 300,
			newPlanner(new RuleSearch(), 5), h -> h.stream()
				.filter(e -> e.payload(Struck.class) != null).count() >= 2);
		
		List<Struck> strikes = history.stream()
			.map(e -> e.payload(Struck.class)).filter(s -> s != null).toList();
		double predicted =
			strikes.stream().mapToDouble(s -> s.option().damage()).sum();
		double damage = initialHealth - targetHealth;
		LOGGER.info("SM: {} {} strikes, damage actual={} predicted={}", name,
			strikes.size(), fmt(damage), fmt(predicted));
		
		if(history.isEmpty()
			|| !history.getFirst().name().startsWith("WindChargeJump"))
			failures.add(name + ": didn't start with a launch: " + history);
		if(strikes.size() < 2)
			failures.add(
				name + ": only " + strikes.size() + " strikes: " + history);
		if(Math.abs(damage - predicted) > Math.max(1.5, 0.05 * predicted))
			failures.add(name + ": dealt " + fmt(damage) + " damage, predicted "
				+ fmt(predicted));
	}
	
	/**
	 * The SpearMace hack itself, with its default settings: it has to find
	 * the golem on its own, strike it twice and let go of every key when
	 * turned off.
	 */
	private void testHack(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		String name = "SpearMace hack";
		reset(context, server);
		int golem = spawnGolem(server, TARGET_X, GROUND_Y, TARGET_Z, true);
		runCommand(server, "tp @p " + TARGET_X + " " + GROUND_Y + " "
			+ (TARGET_Z - 1.5) + " 0 0");
		context.waitFor(mc -> clientEntity(golem) != null);
		context.waitTicks(2);
		
		SpearMaceHack hack = WurstClient.INSTANCE.getHax().spearMaceHack;
		context.runOnClient(mc -> hack.setEnabled(true));
		boolean[] picked = {false};
		List<Entry> history = List.of();
		for(int t = 0; t < 300; t++)
		{
			context.waitTick();
			logServerProjectiles(name, t);
			history = context.computeOnClient(mc -> {
				LivingEntity target = hack.getTarget();
				picked[0] |= target != null && target.getId() == golem;
				return hack.getRunner().history();
			});
			if(history.stream().filter(SpearMaceTest::isStrike).count() >= 2)
				break;
		}
		context.runOnClient(mc -> hack.setEnabled(false));
		context.waitTicks(2);
		logServerProjectiles(name, 300);
		
		boolean[] keys = context
			.computeOnClient(mc -> new boolean[]{mc.options.keyUp.isDown(),
				mc.options.keySprint.isDown(), mc.options.keyJump.isDown()});
		long strikes = history.stream().filter(SpearMaceTest::isStrike).count();
		LOGGER.info("SM: {} picked golem={} strikes={} damage={} history {}",
			name, picked[0], strikes, fmt(initialHealth - targetHealth),
			history);
		if(!picked[0])
			failures.add(name + ": never targeted the golem");
		if(strikes < 2)
			failures.add(name + ": only " + strikes + " strikes: " + history);
		if(keys[0] || keys[1] || keys[2])
			failures.add(name + ": still holding keys after turning off");
	}
	
	/**
	 * Fights the planner-chain scenario for a fixed time with each search,
	 * and compares how much damage they deal.
	 */
	private void testSearchComparison(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		List<String> names = List.of("rule", "beam", "mcts");
		List<Search> searches =
			List.of(new RuleSearch(), new BeamSearch(4, 3), new Mcts(60, 1, 1));
		ArrayList<String> summary = new ArrayList<>();
		double ruleDamage = Double.NaN;
		for(int i = 0; i < searches.size(); i++)
		{
			String name = "Search " + names.get(i);
			reset(context, server);
			int golem = spawnGolem(server, TARGET_X, GROUND_Y, TARGET_Z, true);
			runCommand(server, "tp @p " + TARGET_X + " " + GROUND_Y + " "
				+ (TARGET_Z - 1.5) + " 0 0");
			context.waitFor(mc -> clientEntity(golem) != null);
			context.waitTicks(2);
			
			Planner planner = newPlanner(searches.get(i), 50);
			List<Entry> history =
				runPlanner(context, name, 250, planner, h -> false);
			long strikes =
				history.stream().filter(e -> e.payload(Struck.class) != null
					|| e.payload(Slammed.class) != null).count();
			double damage = initialHealth - targetHealth;
			summary
				.add(String.format("%s: damage=%.1f strikes=%d replan=%.1fms",
					names.get(i), damage, strikes, planner.meanMillis()));
			
			if(i == 0)
				ruleDamage = damage;
			if(damage <= 0)
				failures.add(name + ": dealt no damage: " + history);
			else if(damage < ruleDamage / 2)
				failures.add(name + ": dealt " + fmt(damage)
					+ ", less than half of the rules' " + fmt(ruleDamage));
		}
		LOGGER.info("SM: search comparison {}", summary);
	}
	
	private void runHardTests(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		testFarTarget(context, server, failures);
		testFarBouncingTarget(context, server, failures);
		testFarMovingTarget(context, server, failures, "left",
			new Vec3(FAR_SPEED, 0, 0));
		testFarMovingTarget(context, server, failures, "forward",
			new Vec3(0, 0, FAR_SPEED));
		testFarMovingTarget(context, server, failures, "backward",
			new Vec3(0, 0, -FAR_SPEED));
	}
	
	/**
	 * A target too far away for any single movement technique, so the
	 * planner has to chain at least two different ones before it strikes.
	 */
	private void testFarTarget(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		String name = "Far target";
		reset(context, server);
		spawnGolem(server, TARGET_X, GROUND_Y, FAR_Z, true);
		List<Entry> history = runFar(context, name, failures, mc -> {});
		
		List<String> kinds = movesBeforeFirstStrike(history).stream()
			.map(e -> e.name().replaceAll("\\(.*", "")).distinct().toList();
		LOGGER.info("SM: {} moved with {}", name, kinds);
		if(kinds.size() < 2)
			failures.add(name + ": chained only " + kinds
				+ " before the strike: " + history);
	}
	
	/**
	 * A far target that keeps launching itself with wind charges at its
	 * feet whenever it lands.
	 */
	private void testFarBouncingTarget(ClientGameTestContext context,
		TestServerContext server, List<String> failures)
	{
		String name = "Far bouncing target";
		reset(context, server);
		int golem = spawnGolem(server, TARGET_X, GROUND_Y, FAR_Z, false);
		standStill(server, golem);
		
		int[] cooldown = {0};
		double[] top = {GROUND_Y};
		runFar(context, name, failures, mc -> {
			if(!(mc.overworld().getEntity(golem) instanceof LivingEntity g))
				return;
			top[0] = Math.max(top[0], g.getY());
			if(cooldown[0] > 0)
				cooldown[0]--;
			else if(g.onGround())
			{
				WindCharge charge = EntityTypes.WIND_CHARGE
					.create(mc.overworld(), EntitySpawnReason.COMMAND);
				charge.snapTo(g.getX(), g.getY() + 0.5, g.getZ(), 0, 0);
				charge.setDeltaMovement(0, -1, 0);
				mc.overworld().addFreshEntity(charge);
				cooldown[0] = BOUNCE_COOLDOWN;
			}
		});
		
		LOGGER.info("SM: {} target bounced up to {} blocks", name,
			fmt(top[0] - GROUND_Y));
		if(top[0] - GROUND_Y < 1)
			failures.add(name + ": the target never bounced (setup)");
	}
	
	/**
	 * A far target walking at a steady speed: to the left, away from the
	 * player or toward the player.
	 */
	private void testFarMovingTarget(ClientGameTestContext context,
		TestServerContext server, List<String> failures, String direction,
		Vec3 velocity)
	{
		String name = "Far target moving " + direction;
		reset(context, server);
		int golem = spawnGolem(server, TARGET_X, GROUND_Y, FAR_Z, false);
		standStill(server, golem);
		
		runFar(context, name, failures, mc -> {
			if(mc.overworld().getEntity(golem) instanceof LivingEntity g)
				g.setDeltaMovement(velocity.x, g.getDeltaMovement().y,
					velocity.z);
		});
	}
	
	/**
	 * Lets the beam search planner go after the far target until its
	 * first strike lands, and fails if it never does.
	 */
	private List<Entry> runFar(ClientGameTestContext context, String name,
		List<String> failures, Consumer<MinecraftServer> serverTick)
	{
		context.waitFor(mc -> clientEntity(targetId) != null);
		context.waitTicks(2);
		
		Planner planner = newPlanner(new BeamSearch(4, 3), 50);
		List<Entry> history = runPlanner(context, name, FAR_TICKS, planner,
			h -> h.stream().anyMatch(SpearMaceTest::isStrike), serverTick);
		
		Entry strike = history.stream().filter(SpearMaceTest::isStrike)
			.findFirst().orElse(null);
		LOGGER.info("SM: {} first strike {}, replan={}ms, moves {}", name,
			strike, fmt(planner.meanMillis()), movesBeforeFirstStrike(history));
		if(strike == null)
			failures.add(
				name + ": never struck in " + FAR_TICKS + " ticks: " + history);
		return history;
	}
	
	private static boolean isStrike(Entry e)
	{
		return e.payload(Struck.class) != null
			|| e.payload(Slammed.class) != null;
	}
	
	/** Movement actions that did something, up to the first strike. */
	private static List<Entry> movesBeforeFirstStrike(List<Entry> history)
	{
		ArrayList<Entry> moves = new ArrayList<>();
		for(Entry e : history)
		{
			if(isStrike(e))
				break;
			boolean movement = e.name().startsWith("PearlCatch")
				|| e.name().startsWith("SpearLunge")
				|| e.name().startsWith("WindChargeJump");
			if(movement && e.status() != Runner.Status.FAILED)
				moves.add(e);
		}
		return moves;
	}
	
	/** Keeps a golem with AI from walking off on its own. */
	private static void standStill(TestServerContext server, int id)
	{
		server.runOnServer(mc -> ((LivingEntity)mc.overworld().getEntity(id))
			.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0));
	}
	
	private static Planner newPlanner(Search search, long budgetMillis)
	{
		return new Planner(TargetModel.ballistic(), new Options(),
			new DefaultController(), search, new DamageRace(), budgetMillis);
	}
	
	/**
	 * Lets {@code planner} fight the watched target until {@code done}
	 * accepts the history or {@code maxTicks} pass, logging every tick.
	 */
	private List<Entry> runPlanner(ClientGameTestContext context, String name,
		int maxTicks, Planner planner, Predicate<List<Entry>> done)
	{
		return runPlanner(context, name, maxTicks, planner, done, mc -> {});
	}
	
	/**
	 * @param serverTick
	 *            runs on the server once per tick, to move the target
	 */
	private List<Entry> runPlanner(ClientGameTestContext context, String name,
		int maxTicks, Planner planner, Predicate<List<Entry>> done,
		Consumer<MinecraftServer> serverTick)
	{
		Recorder recorder =
			new Recorder(() -> targetId >= 0 ? clientEntity(targetId) : null);
		Runner[] planRunner = new Runner[1];
		context.runOnClient(mc -> {
			planRunner[0] = new Runner(
				() -> targetId >= 0 ? clientEntity(targetId) : null, planner);
			planRunner[0].start();
			recorder.start();
		});
		
		for(int tick = 0; tick < maxTicks; tick++)
		{
			context.waitTick();
			int t = tick;
			server.runOnServer(serverTick::accept);
			logServerProjectiles(name, t);
			boolean finished = context.computeOnClient(mc -> {
				LOGGER.info(String.format(
					"SM [%s t%d] pos=%s vel=%s rot=(%.1f, %.1f) slot=%d ground=%s%s",
					name, t, fmt(mc.player.position()),
					fmt(mc.player.getDeltaMovement()), mc.player.getYRot(),
					mc.player.getXRot(),
					mc.player.getInventory().getSelectedSlot(),
					mc.player.onGround(), describeClientTarget()));
				return done.test(planRunner[0].history());
			});
			if(finished)
				break;
		}
		
		// let the last hit's damage reach the server log
		context.waitTicks(2);
		logServerProjectiles(name, maxTicks);
		return context.computeOnClient(mc -> {
			Runner r = planRunner[0];
			r.stop();
			recorder.stop();
			lastFrames = recorder.frames();
			replay(mc, name, lastFrames);
			LOGGER.info("SM: {} {} replans, history {}", name, r.replans(),
				r.history());
			return r.history();
		});
	}
	
	/**
	 * Runs one {@link Engage} against the watched target.
	 *
	 * @return what it did, or {@code null} if it failed
	 */
	private Engaged runEngage(ClientGameTestContext context, String name,
		List<String> failures)
	{
		Result r =
			single(run(context, name, 150, rr -> rr.enqueue(new Engage())),
				name, failures);
		Engaged engaged = r == null ? null : r.payload(Engaged.class);
		LOGGER.info("SM: {} decision {}", name,
			engaged == null ? null : engaged.decision());
		return engaged;
	}
	
	private int spawnGolem(TestServerContext server, double x, double y,
		double z, boolean noAi)
	{
		int id = server.computeOnServer(mc -> {
			ServerLevel level = mc.overworld();
			IronGolem golem =
				EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
			golem.snapTo(x, y, z, 0, 0);
			golem.setNoAi(noAi);
			golem.getAttribute(Attributes.MAX_HEALTH)
				.setBaseValue(TARGET_HEALTH);
			golem.setHealth((float)TARGET_HEALTH);
			level.addFreshEntity(golem);
			return golem.getId();
		});
		watchTarget(id);
		return id;
	}
	
	/**
	 * Spawns a golem that falls (it needs AI for that) and puts the player
	 * under it in the same server tick.
	 */
	private int spawnGolemWithPlayer(TestServerContext server, double golemY,
		double playerY)
	{
		int id = spawnGolem(server, TARGET_X + 0.3, golemY, TARGET_Z, false);
		server.runOnServer(mc -> {
			getServerPlayer(mc).connection.teleport(TARGET_X, playerY, TARGET_Z,
				0, -90);
			mc.overworld().getEntity(id).snapTo(TARGET_X + 0.3, golemY,
				TARGET_Z, 0, 0);
		});
		return id;
	}
	
	private int spawnShieldDummy(TestServerContext server)
	{
		int id = server.computeOnServer(mc -> {
			// facing north, toward the player
			shieldDummy = ShieldDummy.spawn(mc, TARGET_X, GROUND_Y, TARGET_Z,
				180, TARGET_HEALTH);
			return shieldDummy.getId();
		});
		watchTarget(id);
		return id;
	}
	
	private void watchTarget(int id)
	{
		targetId = id;
		initialHealth = TARGET_HEALTH;
		targetHealth = TARGET_HEALTH;
	}
	
	private String describeClientTarget()
	{
		LivingEntity target = targetId < 0 ? null : clientEntity(targetId);
		if(target == null)
			return "";
		return String.format(" target=%s tvel=%s", fmt(target.position()),
			fmt(target.getDeltaMovement()));
	}
	
	private static LivingEntity clientEntity(int id)
	{
		return Minecraft.getInstance().level
			.getEntity(id) instanceof LivingEntity e ? e : null;
	}
	
	private static double horizontalGap(ClientGameTestContext context, int id)
	{
		return context
			.computeOnClient(mc -> horizontalDist(mc.player.position(),
				clientEntity(id).position()));
	}
	
	/**
	 * Runs the runner until it is idle, logging every tick.
	 */
	private List<Result> run(ClientGameTestContext context, String name,
		int maxTicks, Consumer<ActionRunner> setup)
	{
		return run(context, name, maxTicks, setup, mc -> {});
	}
	
	private List<Result> run(ClientGameTestContext context, String name,
		int maxTicks, Consumer<ActionRunner> setup, Consumer<Minecraft> perTick)
	{
		Recorder recorder =
			new Recorder(() -> targetId >= 0 ? clientEntity(targetId) : null);
		context.runOnClient(mc -> {
			runner = new ActionRunner(
				() -> targetId >= 0 ? clientEntity(targetId) : null);
			setup.accept(runner);
			runner.start();
			recorder.start();
		});
		
		for(int tick = 0; tick < maxTicks; tick++)
		{
			context.waitTick();
			int t = tick;
			logServerProjectiles(name, t);
			boolean idle = context.computeOnClient(mc -> {
				perTick.accept(mc);
				LOGGER.info(String.format(
					"SM [%s t%d] pos=%s vel=%s rot=(%.1f, %.1f) slot=%d ground=%s%s",
					name, t, fmt(mc.player.position()),
					fmt(mc.player.getDeltaMovement()), mc.player.getYRot(),
					mc.player.getXRot(),
					mc.player.getInventory().getSelectedSlot(),
					mc.player.onGround(), describeClientTarget()));
				return runner.isIdle();
			});
			if(idle)
				break;
		}
		
		return context.computeOnClient(mc -> {
			runner.stop();
			recorder.stop();
			lastFrames = recorder.frames();
			replay(mc, name, lastFrames);
			return runner.results();
		});
	}
	
	/**
	 * Fails if the forward model got anything wrong that it should get
	 * exactly right. Steps where the server handled something a tick early
	 * or late are left out, and a step or two may still be off because of
	 * random projectile spread.
	 */
	private static void checkReplay(NamedReport r, List<String> failures)
	{
		Report f = r.filtered().exact();
		String name = r.name() + " replay";
		check(failures, name, "slot errors", f.slotErrors() == 0);
		check(failures, name, "attack charge errors",
			f.max(StepError::ticker) == 0);
		check(failures, name, "cooldown errors",
			f.max(StepError::windChargeCooldown) == 0
				&& f.max(StepError::pearlCooldown) == 0);
		check(failures, name, "fall distance errors",
			f.max(StepError::fall) < 0.01);
		check(failures, name, "position errors",
			f.steps().stream().filter(s -> s.pos() > 0.01).count() <= 1);
		check(failures, name, "velocity errors",
			f.steps().stream().filter(s -> s.vel() > 0.05).count() <= 2);
		check(failures, name, "target health errors", f.steps().stream()
			.filter(s -> Math.abs(s.targetHealth()) > 1).count() <= 1);
	}
	
	/** Checks the forward model against what really happened. */
	private void replay(Minecraft mc, String name, List<Frame> frames)
	{
		TickSim sim = new TickSim(mc.level, TargetModel.ballistic());
		Report filtered = Replay.filtered(sim, frames);
		Report open = Replay.openLoop(sim, frames);
		LOGGER.info("SM replay [{}] filtered: {}", name, filtered.summary());
		LOGGER.info("SM replay [{}] open loop: {}", name, open.summary());
		for(StepError e : filtered.worse(0.01, 0.01, 0))
			LOGGER.info("SM replay [{}] {}", name, e);
		
		replays.add(new NamedReport(name, filtered, open));
	}
	
	/**
	 * Logs the server's view of thrown projectiles: ground truth for the
	 * timing model.
	 */
	private void logServerProjectiles(String name, int tick)
	{
		String projectiles = server.computeOnServer(mc -> {
			ServerPlayer p = getServerPlayer(mc);
			StringBuilder sb = new StringBuilder();
			int pearlAge = -1;
			int chargeAge = -1;
			for(Entity e : p.level().getEntities(p,
				p.getBoundingBox().inflate(64),
				e -> e instanceof ThrownEnderpearl || e instanceof WindCharge))
			{
				sb.append(String.format(" %s#%d age=%d pos=%s vel=%s",
					e instanceof WindCharge ? "charge" : "pearl", e.getId(),
					e.tickCount, fmt(e.position()), fmt(e.getDeltaMovement())));
				if(e instanceof WindCharge)
					chargeAge = e.tickCount;
				else
					pearlAge = e.tickCount;
			}
			if(serverGap < 0 && pearlAge >= 0 && chargeAge >= 0)
				serverGap = pearlAge - chargeAge;
			if(targetId >= 0
				&& p.level().getEntity(targetId) instanceof LivingEntity t)
			{
				targetHealth = t.getHealth();
				sb.append(String.format(" target=%s health=%.2f using=%s",
					fmt(t.position()), t.getHealth(), t.isUsingItem()));
			}
			return String.format("feet=%s fall=%.2f%s", fmt(p.position()),
				p.fallDistance, sb);
		});
		LOGGER.info("SM server [{} t{}] {}", name, tick, projectiles);
	}
	
	private static Result single(List<Result> results, String name,
		List<String> failures)
	{
		return allDone(results, 1, name, failures) ? results.get(0) : null;
	}
	
	private static boolean allDone(List<Result> results, int expected,
		String name, List<String> failures)
	{
		String problem = null;
		if(results.size() < expected)
			problem =
				"only " + results.size() + "/" + expected + " actions finished";
		else
			for(Result r : results)
				if(r.status() != Status.DONE)
					problem = r.toString();
				
		if(problem == null)
			return true;
		
		LOGGER.warn("SM: {} failed: {}", name, problem);
		if(failures != null)
			failures.add(name + ": " + problem);
		return false;
	}
	
	private void reset(ClientGameTestContext context, TestServerContext server)
	{
		releaseKeys(context);
		targetId = -1;
		if(shieldDummy != null)
		{
			ShieldDummy dummy = shieldDummy;
			server.runOnServer(mc -> dummy.remove(mc));
			shieldDummy = null;
		}
		runCommand(server, "gamemode survival");
		runCommand(server, "kill @e[type=!player]");
		runCommand(server, "tp @p 0.5 -60 0.5 0 0");
		runCommand(server, "clear");
		runCommand(server, "item replace entity @p hotbar." + SPEAR_SLOT
			+ " with netherite_spear[enchantments={\"minecraft:lunge\":3}]");
		runCommand(server,
			"item replace entity @p hotbar.2 with wind_charge 64");
		runCommand(server,
			"item replace entity @p hotbar.3 with ender_pearl 16");
		runCommand(server, "item replace entity @p hotbar.4 with"
			+ " mace[enchantments={\"minecraft:wind_burst\":3}]");
		runCommand(server,
			"item replace entity @p hotbar.5 with netherite_sword");
		runCommand(server,
			"item replace entity @p hotbar.6 with netherite_axe");
		runCommand(server, "effect give @p resistance infinite 4 true");
		runCommand(server, "effect give @p saturation infinite 0 true");
		server.runOnServer(mc -> {
			ServerPlayer p = getServerPlayer(mc);
			p.setHealth(p.getMaxHealth());
			p.resetFallDistance();
		});
		context.runOnClient(_ -> KeyInput.selectSlot(HAND_SLOT));
		
		// wait out the pearl cooldown and let the player settle
		context.waitTicks(25);
	}
	
	private static void setCamera(ClientGameTestContext context,
		CameraType type)
	{
		context.runOnClient(mc -> mc.options.setCameraType(type));
	}
	
	private static void releaseKeys(ClientGameTestContext context)
	{
		context.runOnClient(mc -> {
			IKeyMapping.get(mc.options.keyUp).resetDownState();
			IKeyMapping.get(mc.options.keyDown).resetDownState();
			IKeyMapping.get(mc.options.keyLeft).resetDownState();
			IKeyMapping.get(mc.options.keyRight).resetDownState();
			IKeyMapping.get(mc.options.keySprint).resetDownState();
			IKeyMapping.get(mc.options.keyJump).resetDownState();
			mc.player.setSprinting(false);
		});
	}
	
	private static Vec3 playerPos(ClientGameTestContext context)
	{
		return context.computeOnClient(mc -> mc.player.position());
	}
	
	private static int countPearls(ClientGameTestContext context)
	{
		return context.computeOnClient(
			mc -> mc.player.getInventory().countItem(Items.ENDER_PEARL));
	}
	
	private static double horizontalDist(Vec3 a, Vec3 b)
	{
		return Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.z - b.z, 2));
	}
	
	private static ServerPlayer getServerPlayer(MinecraftServer mc)
	{
		return mc.getPlayerList().getPlayers().getFirst();
	}
	
	private static String fmt(double d)
	{
		return String.format("%.2f", d);
	}
	
	private static String fmt(Vec3 v)
	{
		return v == null ? "null"
			: String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
	}
}
