/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.wurstclient.WurstClient;
import net.wurstclient.gametest.gym.GymPayloads.Start;
import net.wurstclient.gametest.gym.GymPayloads.Stop;
import net.wurstclient.hacks.SpearMaceHack;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.plan.Options;
import net.wurstclient.hacks.spearmace.plan.Outcome;
import net.wurstclient.hacks.spearmace.plan.Plan;
import net.wurstclient.hacks.spearmace.plan.Planner;
import net.wurstclient.hacks.spearmace.plan.Runner;
import net.wurstclient.hacks.spearmace.plan.Runner.Decision;
import net.wurstclient.hacks.spearmace.plan.Runner.DecisionListener;
import net.wurstclient.hacks.spearmace.plan.Runner.Entry;
import net.wurstclient.hacks.spearmace.plan.SearchTrace;
import net.wurstclient.hacks.spearmace.plan.SearchTrace.Edge;
import net.wurstclient.hacks.spearmace.plan.SearchTrace.Visits;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.viz.Preview;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SliderSetting;

/**
 * One gym bot: fights with SpearMace between the referee's {@link Start}
 * and {@link Stop}, and writes a JSON line for each of SpearMace's
 * decisions and each action that ends.
 *
 * <p>
 * A decision line lists the actions SpearMace could take there
 * ({@link Options}), what the acting search and, if there is one, the
 * expert search thought of each, and which one was taken. The expert
 * searches the same observed state as the acting planner, in the same
 * tick, so its labels are for exactly the states the bot visits.
 *
 * <p>
 * Runs in every gym client, so the host's own player can be a bot too.
 */
public final class GymAgent implements DecisionListener
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("SpearMaceGym");
	private static final Minecraft MC = WurstClient.MC;
	
	/** How long the agent waits between attempts to join the host. */
	private static final int CONNECT_RETRY_TICKS = 60;
	
	private Episode episode;
	private int connectCooldown;
	
	private Start pendingStart;
	
	/** The runner that has the episode's planner and this listener. */
	private Runner attached;
	
	/**
	 * Everything about the episode being played.
	 *
	 * @param expert
	 *            a separate expert, {@code null} if the acting planner is
	 *            the expert ({@code actIsExpert}) or there is none
	 * @param worker
	 *            writes the file and runs the expert, one thing at a time,
	 *            so that a slow expert never holds up the fight
	 */
	private record Episode(Start start, BufferedWriter out, int startTick,
		long startNanos, Planner acting, Planner expert, boolean actIsExpert,
		double oldRange, boolean oldTracing, ExecutorService worker,
		AtomicBoolean expertBusy, AtomicInteger labeled, AtomicInteger skipped)
	{}
	
	public void register()
	{
		GymJson.register(Kit.class, GymAgent::kit);
		ClientPlayNetworking.registerGlobalReceiver(Start.TYPE,
			(start, context) -> pendingStart = start);
		ClientPlayNetworking.registerGlobalReceiver(Stop.TYPE,
			(stop, context) -> {
				if(pendingStart != null
					&& pendingStart.episode() == stop.episode())
					pendingStart = null;
				stop(stop.episode(), stop.reason());
			});
		ClientPlayConnectionEvents.DISCONNECT
			.register((handler, client) -> stop(-1, "disconnected"));
		
		// Wurst turns its saved hacks back on once the world has loaded,
		// which gives SpearMace a new runner if a Start came before that
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			// a Start can come while the world is still loading
			if(pendingStart != null && mc.player != null)
			{
				start(pendingStart);
				pendingStart = null;
			}
			
			SpearMaceHack hack = WurstClient.INSTANCE.getHax().spearMaceHack;
			if(episode != null && mc.player != null
				&& (!hack.isEnabled() || hack.getRunner() != attached))
				attach();
		});
	}
	
	/** Makes SpearMace fight with the episode's planner, reporting here. */
	private void attach()
	{
		SpearMaceHack hack = WurstClient.INSTANCE.getHax().spearMaceHack;
		hack.setEnabled(true);
		attached = hack.getRunner();
		attached.setPlanner(episode.acting);
		attached.setListener(this);
	}
	
	/**
	 * Makes this client a dedicated bot: quiet, never paused, and joining
	 * the gym at {@code address} whenever it isn't connected.
	 *
	 * @param headless
	 *            whether nobody watches this client, so it may draw as
	 *            little as possible
	 */
	public void connectTo(String address, boolean headless)
	{
		// the options aren't loaded yet while mods initialize
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			mc.options.pauseOnLostFocus = false;
			// the bots must see each other across most of the arena, even
			// when they don't draw anything
			mc.options.renderDistance().set(10);
			mc.options.framerateLimit().set(headless ? 10 : 30);
			mc.options.getSoundSourceOptionInstance(SoundSource.MASTER)
				.set(0.0);
			mc.options.skipMultiplayerWarning = true;
			mc.options.onboardAccessibility = false;
		});
		
		// Wurst remembers enabled hacks, so a bot that was killed mid-fight
		// would start fighting as soon as it joins
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			if(episode == null)
				WurstClient.INSTANCE.getHax().spearMaceHack.setEnabled(false);
		});
		
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if(mc.level != null || !(mc.gui.screen() instanceof TitleScreen
				|| mc.gui.screen() instanceof DisconnectedScreen))
				return;
			if(connectCooldown-- > 0)
				return;
			
			connectCooldown = CONNECT_RETRY_TICKS;
			LOGGER.info("Joining {}", address);
			ConnectScreen.startConnecting(new TitleScreen(), mc,
				ServerAddress.parseString(address),
				new ServerData("SpearMace gym", address, ServerData.Type.LAN),
				false, null);
		});
	}
	
	private void start(Start start)
	{
		stop(-1, "restarted");
		SpearMaceHack hack = WurstClient.INSTANCE.getHax().spearMaceHack;
		GymConfig act = GymConfig.fromJson(start.config());
		GymConfig expertConfig = start.expert().isEmpty() ? null
			: GymConfig.fromJson(start.expert());
		
		BufferedWriter out;
		try
		{
			Path dir = Path.of(start.outDir());
			Files.createDirectories(dir);
			out = Files.newBufferedWriter(
				dir.resolve("decisions-" + start.side() + ".jsonl"),
				StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
		}catch(IOException e)
		{
			LOGGER.error("Can't write gym data", e);
			return;
		}
		
		// pearl catches easily carry the bots past the default range
		SliderSetting range = range(hack);
		double oldRange = range.getValue();
		range.setValue(range.getMaximum());
		boolean oldTracing = Preview.isTracing();
		Preview.setTracing(true);
		
		// the acting plan is the expert's when they are the same config
		boolean actIsExpert = act.equals(expertConfig);
		Planner expert = expertConfig == null || actIsExpert ? null
			: GymPlanners.of(expertConfig);
		Planner acting = GymPlanners.exploring(GymPlanners.of(act),
			start.explore(), new Random(start.seed()));
		ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "SpearMace gym " + start.side());
			t.setDaemon(true);
			return t;
		});
		episode =
			new Episode(start, out, MC.player.tickCount, System.nanoTime(),
				acting, expert, actIsExpert, oldRange, oldTracing, worker,
				new AtomicBoolean(), new AtomicInteger(), new AtomicInteger());
		
		attach();
		LOGGER.info("Episode {} as {} against {}: acting {}, expert {}",
			start.episode(), start.side(), start.opponent(), act.label(),
			expertConfig == null ? "none"
				: actIsExpert ? "same" : expertConfig.label());
	}
	
	private static SliderSetting range(SpearMaceHack hack)
	{
		Setting setting = hack.getSettings().get("range");
		if(!(setting instanceof SliderSetting slider))
			throw new IllegalStateException("SpearMace has no range");
		return slider;
	}
	
	/**
	 * @param id
	 *            the episode to stop, -1 for whichever is running
	 */
	private void stop(int id, String reason)
	{
		Episode e = episode;
		if(e == null || id >= 0 && id != e.start.episode())
			return;
		
		SpearMaceHack hack = WurstClient.INSTANCE.getHax().spearMaceHack;
		Runner runner = hack.getRunner();
		JsonObject line = header("stop", e);
		line.addProperty("reason", reason);
		if(runner != null)
		{
			runner.setListener(null);
			line.addProperty("replans", runner.replans());
		}
		
		hack.setEnabled(false);
		attached = null;
		range(hack).setValue(e.oldRange);
		Preview.setTracing(e.oldTracing);
		episode = null;
		
		// after any expert label that is still being worked on
		e.worker.execute(() -> {
			line.addProperty("expertLabeled", e.labeled.get());
			line.addProperty("expertSkipped", e.skipped.get());
			writeNow(e, line);
			try
			{
				e.out.close();
			}catch(IOException ex)
			{
				LOGGER.error("Can't close gym data", ex);
			}
		});
		e.worker.shutdown();
	}
	
	@Override
	public void onDecision(Decision d)
	{
		// SpearMace replans every tick while it has no target
		Episode e = episode;
		if(e == null || d.now().target() == null)
			return;
		
		List<Action> candidates = new Options().of(MC.level, d.now());
		
		JsonObject line = header("decision", e);
		line.addProperty("tick", d.now().self().tick() - e.startTick);
		line.add("triggers", GymJson.of(d.triggers()));
		LivingEntity target =
			WurstClient.INSTANCE.getHax().spearMaceHack.getTarget();
		line.addProperty("targetName",
			target == null ? null : target.getName().getString());
		line.add("self", GymJson.of(d.now().self()));
		line.add("target", GymJson.of(d.now().target()));
		line.add("candidates", candidates(candidates, d.trace()));
		
		JsonObject acted = search(candidates, d.plan(), d.trace(), d.nanos());
		acted.addProperty("explored",
			GymPlanners.EXPLORE.equals(d.plan().why()));
		line.add("acted", acted);
		line.add("plan", plan(d.plan()));
		
		if(e.expert != null)
		{
			// labels don't have to be on time, only for the right state,
			// but a busy worker would fall further behind with each one
			if(!e.expertBusy.compareAndSet(false, true))
			{
				e.skipped.incrementAndGet();
				write(e, line);
				return;
			}
			Level level = MC.level;
			e.worker.execute(() -> {
				try
				{
					long start = System.nanoTime();
					Plan plan = e.expert.replan(level, d.now());
					line.add("expert", search(candidates, plan,
						e.expert.lastTrace(), System.nanoTime() - start));
					e.labeled.incrementAndGet();
				}catch(RuntimeException ex)
				{
					LOGGER.error("Expert failed", ex);
					line.addProperty("expertError", ex.toString());
				}finally
				{
					e.expertBusy.set(false);
				}
				writeNow(e, line);
			});
			return;
		}
		
		if(e.actIsExpert)
		{
			// what the search picked, even if exploration overrode it
			Plan searched = GymPlanners.searched(e.acting, d.plan());
			line.add("expert",
				search(candidates, searched, d.trace(), d.nanos()));
			e.labeled.incrementAndGet();
		}
		write(e, line);
	}
	
	@Override
	public void onActionEnd(Entry entry)
	{
		Episode e = episode;
		if(e == null)
			return;
		
		JsonObject line = header("action_end", e);
		line.addProperty("name", entry.name());
		line.addProperty("status", entry.status().name());
		line.addProperty("reason", entry.reason());
		line.addProperty("startTick", entry.startTick() - e.startTick);
		line.addProperty("endTick", entry.endTick() - e.startTick);
		line.addProperty("payloadType", entry.payload() == null ? null
			: entry.payload().getClass().getSimpleName());
		line.add("payload", GymJson.of(entry.payload()));
		write(e, line);
	}
	
	/**
	 * The action space at a decision: every action SpearMace could take,
	 * with what the model predicts for it when the acting search tried it.
	 */
	private static JsonArray candidates(List<Action> candidates,
		SearchTrace trace)
	{
		JsonArray array = new JsonArray();
		for(Action a : candidates)
		{
			JsonObject json = new JsonObject();
			json.addProperty("action", a.name());
			json.addProperty("type", a.getClass().getSimpleName());
			json.add("params", GymJson.of(a));
			Edge edge = rootEdge(trace, a);
			if(edge != null)
				json.add("outcome", outcome(edge.outcome()));
			array.add(json);
		}
		return array;
	}
	
	/**
	 * What a search made of each candidate, in the candidates' order, and
	 * which one it picked: these are the labels a policy learns from.
	 */
	private static JsonObject search(List<Action> candidates, Plan plan,
		SearchTrace trace, long nanos)
	{
		JsonObject json = new JsonObject();
		json.addProperty("why", plan.why());
		json.addProperty("ms", nanos / 1e6);
		json.addProperty("chosen", plan.actions().isEmpty() ? -1
			: candidates.indexOf(plan.actions().getFirst()));
		json.add("value", GymJson.of(plan.value()));
		
		double[] best = bestPerRoot(trace);
		JsonArray rank = new JsonArray();
		JsonArray bestArray = new JsonArray();
		JsonArray visits = new JsonArray();
		JsonArray mean = new JsonArray();
		boolean counted = false;
		for(Action a : candidates)
		{
			Edge edge = rootEdge(trace, a);
			rank.add(GymJson.of(edge == null ? null : edge.rank()));
			bestArray.add(GymJson.of(edge == null ? null : best[edge.index()]));
			Visits v = edge == null ? null : trace.visitsOf(edge.outcome());
			counted |= v != null;
			visits.add(v == null ? 0 : v.visits());
			mean.add(GymJson.of(v == null ? null : v.mean()));
		}
		json.add("rank", rank);
		json.add("best", bestArray);
		if(counted)
		{
			json.add("visits", visits);
			json.add("mean", mean);
		}
		return json;
	}
	
	/**
	 * For each root edge (by index), the best rank anywhere below it,
	 * which is what the search thinks that first action is worth. NaN
	 * where nothing below it worked.
	 */
	private static double[] bestPerRoot(SearchTrace trace)
	{
		if(trace == null)
			return new double[0];
		
		List<Edge> edges = trace.edges();
		double[] best = new double[edges.size()];
		Arrays.fill(best, Double.NaN);
		for(Edge edge : edges)
		{
			Edge root = edge;
			while(root.parent() >= 0)
				root = edges.get(root.parent());
			if(!Double.isNaN(edge.rank()) && (Double.isNaN(best[root.index()])
				|| edge.rank() > best[root.index()]))
				best[root.index()] = edge.rank();
		}
		return best;
	}
	
	/** The first time the search played {@code a} from the root. */
	private static Edge rootEdge(SearchTrace trace, Action a)
	{
		if(trace == null)
			return null;
		for(Edge edge : trace.edges())
			if(edge.depth() == 1 && edge.outcome().action().equals(a))
				return edge;
		return null;
	}
	
	private static JsonObject plan(Plan plan)
	{
		JsonObject json = new JsonObject();
		JsonArray expected = new JsonArray();
		for(Outcome o : plan.expected())
			expected.add(outcome(o));
		json.add("expected", expected);
		json.add("value", GymJson.of(plan.value()));
		json.addProperty("why", plan.why());
		return json;
	}
	
	/** An outcome without its steps, which would be most of the file. */
	private static JsonObject outcome(Outcome o)
	{
		JsonObject json = new JsonObject();
		json.addProperty("action", o.action().name());
		json.addProperty("ticks", o.ticks());
		json.add("dealt", GymJson.of(o.damageDealt()));
		json.add("taken", GymJson.of(o.damageTaken()));
		json.add("chance", GymJson.of(o.chance()));
		json.add("events", GymJson.of(o.events()));
		json.addProperty("failure", o.failure());
		json.add("endSelfPos", GymJson.vec(o.end().self().pos()));
		return json;
	}
	
	private static JsonObject kit(Kit kit)
	{
		JsonObject json = new JsonObject();
		json.addProperty("selected", kit.selected());
		JsonArray slots = new JsonArray();
		JsonArray cooldowns = new JsonArray();
		for(int slot = 0; slot < 9; slot++)
		{
			ItemStack stack = kit.get(slot);
			slots.add(GymJson.item(stack));
			cooldowns.add(stack.isEmpty() ? 0 : kit.cooldown(stack));
		}
		json.add("slots", slots);
		json.add("cooldowns", cooldowns);
		return json;
	}
	
	private static JsonObject header(String type, Episode e)
	{
		JsonObject line = new JsonObject();
		line.addProperty("type", type);
		line.addProperty("episode", e.start.episode());
		line.addProperty("side", e.start.side());
		line.addProperty("clientTick",
			MC.player == null ? -1 : MC.player.tickCount - e.startTick);
		// a client tick count falling behind this shows lag
		line.addProperty("wallMs", (System.nanoTime() - e.startNanos) / 1e6);
		return line;
	}
	
	/** Writes {@code line} after everything queued before it. */
	private static void write(Episode e, JsonObject line)
	{
		e.worker.execute(() -> writeNow(e, line));
	}
	
	/** Only on the worker, which owns the file. */
	private static void writeNow(Episode e, JsonObject line)
	{
		try
		{
			e.out.write(line.toString());
			e.out.newLine();
			// the referee kills the bot clients when it is done
			e.out.flush();
		}catch(IOException ex)
		{
			LOGGER.error("Can't write gym data", ex);
		}
	}
}
