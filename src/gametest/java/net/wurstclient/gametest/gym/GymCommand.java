/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /gym}: set up an arena in the current world and run the gym in
 * it. See {@link SpearMaceGym} for the whole workflow.
 */
public final class GymCommand
{
	private static final String USAGE = "/gym start [episodes]"
		+ " [act=<cfg>] [a=<cfg>] [b=<cfg>] [expert=<cfg>] [explore=P]"
		+ " [tps=N] [pairs=N] [mirror] [self] [noagents] [seed=N] [hours=H]"
		+ " [ticks=N] [health=N] [apples=N] [armor]; cfg: rules, beam:WxD,"
		+ " mcts:N, random," + " expert";
	
	/**
	 * The expert stops when its search is done, not at a time limit, so
	 * this only guards against a search that never ends.
	 */
	private static final int EXPERT_BUDGET = 120_000;
	
	/** Gets the server ready for bots to join and says on which port. */
	@FunctionalInterface
	public interface Host
	{
		public int prepare(MinecraftServer server);
	}
	
	private final Host host;
	private GymArena arena;
	private GymRun run;
	
	/**
	 * @param host
	 *            for a dedicated server, just its port; a singleplayer
	 *            world has to be opened to LAN first
	 */
	public GymCommand(Host host)
	{
		this.host = host;
	}
	
	/** The running gym, or {@code null}. */
	public GymRun run()
	{
		return run != null && run.isRunning() ? run : null;
	}
	
	public void register(CommandDispatcher<CommandSourceStack> dispatcher)
	{
		dispatcher.register(Commands.literal("gym")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("arena")
				.then(Commands.argument("from", BlockPosArgument.blockPos())
					.then(Commands.argument("to", BlockPosArgument.blockPos())
						.executes(this::setArena)))
				.then(Commands.literal("clear").executes(c -> edit(c, a -> {
					a.clearBox();
					return "Arena cleared, fights happen around /gym start";
				}))))
			.then(Commands.literal("spawn").then(Commands.literal("add")
				.executes(c -> addSpawn(c, c.getSource().getPosition()))
				.then(Commands.argument("pos", Vec3Argument.vec3()).executes(
					c -> addSpawn(c, Vec3Argument.getVec3(c, "pos")))))
				.then(Commands.literal("clear").executes(c -> edit(c, a -> {
					a.spawns().clear();
					return "Spawns cleared, starts are sampled on the floor";
				}))))
			.then(Commands.literal("kit").executes(c -> edit(c, a -> {
				a.setKit(c.getSource().getPlayerOrException());
				return "Kit set to your inventory";
			})).then(Commands.literal("default").executes(c -> edit(c, a -> {
				a.clearKit();
				return "Kit set to the SpearMaceTest kit";
			}))))
			.then(Commands.literal("start").executes(c -> start(c, ""))
				.then(Commands
					.argument("options", StringArgumentType.greedyString())
					.executes(c -> start(c,
						StringArgumentType.getString(c, "options")))))
			.then(Commands.literal("stop").executes(this::stop))
			.then(Commands.literal("status").executes(this::status)));
	}
	
	private GymArena arena(MinecraftServer server)
	{
		if(arena == null)
			arena = GymArena.load(server);
		return arena;
	}
	
	@FunctionalInterface
	private interface ArenaEdit
	{
		String edit(GymArena arena) throws CommandSyntaxException;
	}
	
	private int edit(CommandContext<CommandSourceStack> c, ArenaEdit edit)
		throws CommandSyntaxException
	{
		MinecraftServer server = c.getSource().getServer();
		String message = edit.edit(arena(server));
		arena(server).save(server);
		c.getSource().sendSuccess(() -> Component.literal(message), false);
		return 1;
	}
	
	private int setArena(CommandContext<CommandSourceStack> c)
		throws CommandSyntaxException
	{
		BlockPos from = BlockPosArgument.getBlockPos(c, "from");
		BlockPos to = BlockPosArgument.getBlockPos(c, "to");
		return edit(c, a -> {
			a.setBox(from, to);
			return "Arena set to " + from.toShortString() + " - "
				+ to.toShortString();
		});
	}
	
	private int addSpawn(CommandContext<CommandSourceStack> c, Vec3 pos)
		throws CommandSyntaxException
	{
		return edit(c, a -> {
			a.spawns().add(pos);
			return "Spawn " + a.spawns().size() + " added";
		});
	}
	
	private int start(CommandContext<CommandSourceStack> c, String options)
		throws CommandSyntaxException
	{
		CommandSourceStack source = c.getSource();
		MinecraftServer server = source.getServer();
		if(run() != null)
		{
			source.sendFailure(Component.literal("The gym is already running"));
			return 0;
		}
		
		Map<String, String> opts = new HashMap<>();
		int episodes = 20;
		for(String token : options.trim().split("\\s+"))
		{
			if(token.isEmpty())
				continue;
			if(token.matches("\\d+"))
				episodes = Integer.parseInt(token);
			else
			{
				String[] kv = token.split("=", 2);
				opts.put(kv[0].toLowerCase(), kv.length > 1 ? kv[1] : "true");
			}
		}
		
		GymRun.Settings settings;
		try
		{
			long seed = Long.parseLong(opts.getOrDefault("seed",
				Long.toString(System.currentTimeMillis())));
			double hours = Double.parseDouble(opts.getOrDefault("hours", "0"));
			String act = opts.getOrDefault("act", "random");
			String specA = opts.getOrDefault("a", act);
			String specB = opts.getOrDefault("b", act);
			GymConfig expert = opts.containsKey("expert")
				? GymConfig.parse(opts.get("expert"), new Random(seed))
					.withBudget(EXPERT_BUDGET)
				: null;
			// fail now rather than at the first episode
			for(String spec : new String[]{specA, specB})
				if(!spec.equalsIgnoreCase("expert"))
					GymConfig.parse(spec, new Random());
				else if(expert == null)
					throw new IllegalArgumentException(
						"act=expert needs expert=<cfg>");
			settings = new GymRun.Settings(hours > 0 ? 0 : episodes,
				hours > 0 ? System.currentTimeMillis() + (long)(hours * 3.6e6)
					: 0,
				Integer.parseInt(opts.getOrDefault("ticks", "600")),
				Double.parseDouble(opts.getOrDefault("health", "20")), specA,
				specB, opts.containsKey("mirror"), seed, outputDir(), expert,
				Double.parseDouble(opts.getOrDefault("explore", "0")),
				Float.parseFloat(opts.getOrDefault("tps", "20")),
				Integer.parseInt(opts.getOrDefault("pairs", "1")),
				Integer.parseInt(opts.getOrDefault("apples", "16")),
				opts.containsKey("armor"));
			if(settings.pairs() < 1 || settings.tickRate() <= 0)
				throw new IllegalArgumentException(
					"pairs and tps must be positive");
		}catch(IllegalArgumentException e)
		{
			source
				.sendFailure(Component.literal(e.getMessage() + "\n" + USAGE));
			return 0;
		}
		
		boolean self = opts.containsKey("self");
		ArrayList<String[]> players = new ArrayList<>();
		for(int i = 0; i < settings.pairs(); i++)
		{
			String prefix = "Gym-" + (settings.pairs() > 1 ? i : "");
			players.add(new String[]{self && i == 0
				? source.getPlayerOrException().getScoreboardName()
				: prefix + "A", prefix + "B"});
		}
		
		// mobs would be targets too
		GymReferee.command(server, "gamerule spawn_mobs false");
		// the bots log in offline, under made-up names
		server.setUsesAuthentication(false);
		if(server.isUsingWhitelist())
			for(String[] pair : players)
				for(String name : pair)
					server.getPlayerList().getWhiteList().add(
						new UserWhiteListEntry(NameAndId.createOffline(name)));
		String address = "localhost:" + host.prepare(server);
		
		AgentProcess agents = null;
		if(!opts.containsKey("noagents"))
		{
			List<String> missing = new ArrayList<>();
			for(String[] pair : players)
				for(String name : pair)
					if(server.getPlayerList().getPlayerByName(name) == null)
						missing.add(name);
			try
			{
				agents = missing.isEmpty() ? null
					: AgentProcess.start(missing, address, settings.outDir());
			}catch(IOException e)
			{
				source.sendFailure(
					Component.literal("Can't start the bots: " + e));
				return 0;
			}
		}
		
		AABB box = arena(server).box(source.getPosition());
		run = new GymRun(server, arena(server), box, settings, players, agents);
		source.sendSuccess(() -> Component.literal("SpearMace gym started at "
			+ address + ", seed " + settings.seed() + ", " + settings.pairs()
			+ " pair(s) at " + settings.tickRate() + " ticks/s"
			+ (arena(server).hasBox() ? ""
				: ", arena 128x128 around here (set one with /gym arena)")
			+ ". Data in " + settings.outDir()), true);
		return 1;
	}
	
	private int stop(CommandContext<CommandSourceStack> c)
	{
		GymRun r = run();
		if(r == null)
		{
			c.getSource()
				.sendFailure(Component.literal("The gym isn't running"));
			return 0;
		}
		r.finish("stopped by " + c.getSource().getTextName());
		return 1;
	}
	
	private int status(CommandContext<CommandSourceStack> c)
	{
		GymRun r = run();
		GymArena a = arena(c.getSource().getServer());
		String message = (r == null ? "Not running" : r.status()) + "\nArena: "
			+ (a.hasBox() ? "set" : "none") + ", " + a.spawns().size()
			+ " spawns, " + (a.hasKit() ? "custom" : "default") + " kit";
		c.getSource().sendSuccess(() -> Component.literal(message), false);
		return 1;
	}
	
	/** One folder per run, kept out of the run dir. */
	private static Path outputDir()
	{
		String env = System.getenv("WURST_GYM_DIR");
		String run = LocalDateTime.now()
			.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		if(env != null)
			return Path.of(env).resolve(run);
		
		// build/run/spearMaceGym -> the project
		Path cwd = Path.of("").toAbsolutePath();
		boolean inRunDir = cwd.getParent() != null
			&& cwd.getParent().getFileName().toString().equals("run")
			&& cwd.getNameCount() >= 3;
		return (inRunDir ? cwd.getParent().getParent().getParent() : cwd)
			.resolve("spearmace-gym").resolve(run);
	}
}
