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
import java.util.Map;

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
import net.wurstclient.WurstClient;
import net.wurstclient.gametest.gym.GymPayloads.Start;
import net.wurstclient.gametest.gym.GymPayloads.Stop;
import net.wurstclient.hacks.SpearMaceHack;
import net.wurstclient.hacks.spearmace.plan.Outcome;
import net.wurstclient.hacks.spearmace.plan.Plan;
import net.wurstclient.hacks.spearmace.plan.Runner;
import net.wurstclient.hacks.spearmace.plan.Runner.Decision;
import net.wurstclient.hacks.spearmace.plan.Runner.DecisionListener;
import net.wurstclient.hacks.spearmace.plan.Runner.Entry;
import net.wurstclient.hacks.spearmace.plan.SearchTrace;
import net.wurstclient.hacks.spearmace.plan.SearchTrace.Edge;
import net.wurstclient.hacks.spearmace.viz.Preview;

/**
 * One gym bot: fights with SpearMace between the referee's {@link Start}
 * and {@link Stop}, and writes a JSON line for each of SpearMace's
 * decisions and each action that ends. Runs in every gym client, so the
 * host's own player can be a bot too.
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
	
	/** Everything about the episode being played. */
	private record Episode(Start start, BufferedWriter out, int startTick,
		long startNanos, Map<String, Object> oldSettings, boolean oldTracing)
	{}
	
	public void register()
	{
		ClientPlayNetworking.registerGlobalReceiver(Start.TYPE,
			(start, context) -> start(start));
		ClientPlayNetworking.registerGlobalReceiver(Stop.TYPE,
			(stop, context) -> stop(stop.episode(), stop.reason()));
		ClientPlayConnectionEvents.DISCONNECT
			.register((handler, client) -> stop(-1, "disconnected"));
	}
	
	/**
	 * Makes this client a dedicated bot: quiet, never paused, and joining
	 * the host at {@code port} whenever it isn't connected.
	 */
	public void connectTo(int port)
	{
		// the options aren't loaded yet while mods initialize
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			mc.options.pauseOnLostFocus = false;
			mc.options.renderDistance().set(6);
			mc.options.framerateLimit().set(30);
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
			String address = "localhost:" + port;
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
		GymConfig config = GymConfig.fromJson(start.config());
		
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
		
		Map<String, Object> oldSettings = config.apply(hack);
		boolean oldTracing = Preview.isTracing();
		Preview.setTracing(true);
		episode = new Episode(start, out, MC.player.tickCount,
			System.nanoTime(), oldSettings, oldTracing);
		hack.setEnabled(true);
		hack.getRunner().setListener(this);
		LOGGER.info("Episode {} as {} against {}: {}", start.episode(),
			start.side(), start.opponent(), config.label());
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
		write(line);
		
		hack.setEnabled(false);
		GymConfig.restore(hack, e.oldSettings);
		Preview.setTracing(e.oldTracing);
		episode = null;
		try
		{
			e.out.close();
		}catch(IOException ex)
		{
			LOGGER.error("Can't close gym data", ex);
		}
	}
	
	@Override
	public void onDecision(Decision d)
	{
		// SpearMace replans every tick while it has no target
		Episode e = episode;
		if(e == null || d.now().target() == null)
			return;
		
		JsonObject line = header("decision", e);
		line.addProperty("tick", d.now().self().tick() - e.startTick);
		line.add("triggers", GymJson.of(d.triggers()));
		line.addProperty("ms", d.nanos() / 1e6);
		LivingEntity target =
			WurstClient.INSTANCE.getHax().spearMaceHack.getTarget();
		line.addProperty("targetName",
			target == null ? null : target.getName().getString());
		line.add("self", GymJson.of(d.now().self()));
		line.add("target", GymJson.of(d.now().target()));
		line.add("candidates", candidates(d.trace()));
		line.add("plan", plan(d.plan()));
		write(line);
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
		write(line);
	}
	
	/** What the search found for each action it tried first. */
	private static JsonArray candidates(SearchTrace trace)
	{
		JsonArray array = new JsonArray();
		if(trace == null)
			return array;
		
		for(Edge edge : trace.edges())
		{
			if(edge.depth() != 1)
				continue;
			
			JsonObject json = outcome(edge.outcome());
			json.add("pathScore", GymJson.of(edge.pathScore()));
			json.add("rank", GymJson.of(edge.rank()));
			json.addProperty("note", trace.noteOf(edge.outcome()));
			array.add(json);
		}
		return array;
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
		json.addProperty("detail", o.action().toString());
		json.addProperty("ticks", o.ticks());
		json.add("dealt", GymJson.of(o.damageDealt()));
		json.add("taken", GymJson.of(o.damageTaken()));
		json.add("chance", GymJson.of(o.chance()));
		json.add("events", GymJson.of(o.events()));
		json.addProperty("failure", o.failure());
		json.add("endSelfPos", GymJson.vec(o.end().self().pos()));
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
		// a client tick count falling behind this shows lag, e.g. App Nap
		line.addProperty("wallMs", (System.nanoTime() - e.startNanos) / 1e6);
		return line;
	}
	
	private void write(JsonObject line)
	{
		Episode e = episode;
		if(e == null)
			return;
		
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
