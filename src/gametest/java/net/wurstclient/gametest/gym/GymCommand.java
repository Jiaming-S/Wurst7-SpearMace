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

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.MinecraftServer.MultiplayerScope;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /gym}: set up an arena in the current world and run the gym in
 * it. See {@link SpearMaceGym} for the whole workflow.
 */
public final class GymCommand
{
	private static final String USAGE = "/gym start [episodes]"
		+ " [a=<cfg>] [b=<cfg>] [mirror] [self] [noagents] [seed=N]"
		+ " [hours=H] [ticks=N] [health=N]; cfg: rules, beam:WxD, mcts:N, random";
	
	private final int port;
	private GymArena arena;
	private GymReferee referee;
	
	public GymCommand(int port)
	{
		this.port = port;
	}
	
	/** The running referee, or {@code null}. */
	public GymReferee referee()
	{
		return referee != null && referee.isRunning() ? referee : null;
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
		if(referee() != null)
		{
			source.sendFailure(Component.literal("The gym is already running"));
			return 0;
		}
		if(!(source.getServer() instanceof IntegratedServer server))
		{
			source.sendFailure(
				Component.literal("The gym runs in a singleplayer world"));
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
		
		long seed;
		GymReferee.Settings settings;
		try
		{
			seed = Long.parseLong(opts.getOrDefault("seed",
				Long.toString(System.currentTimeMillis())));
			double hours = Double.parseDouble(opts.getOrDefault("hours", "0"));
			String specA = opts.getOrDefault("a", "random");
			String specB = opts.getOrDefault("b", "random");
			// fail now rather than at the first episode
			GymConfig.parse(specA, new Random());
			GymConfig.parse(specB, new Random());
			settings = new GymReferee.Settings(hours > 0 ? 0 : episodes,
				hours > 0 ? System.currentTimeMillis() + (long)(hours * 3.6e6)
					: 0,
				Integer.parseInt(opts.getOrDefault("ticks", "600")),
				Double.parseDouble(opts.getOrDefault("health", "20")), specA,
				specB, opts.containsKey("mirror"), seed, outputDir());
		}catch(IllegalArgumentException e)
		{
			source
				.sendFailure(Component.literal(e.getMessage() + "\n" + USAGE));
			return 0;
		}
		
		boolean self = opts.containsKey("self");
		String[] players =
			{self ? source.getPlayerOrException().getScoreboardName() : "Gym-A",
				"Gym-B"};
		
		// mobs would be targets too
		GymReferee.command(server, "gamerule spawn_mobs false");
		
		// the bots log in offline, under made-up names
		server.setUsesAuthentication(false);
		if(!server.isPublished())
			Minecraft.getInstance().execute(
				() -> server.publishServer(MultiplayerScope.LAN, false, port));
		
		AgentProcess agents = null;
		if(!opts.containsKey("noagents"))
		{
			List<String> missing = new ArrayList<>();
			for(String name : players)
				if(server.getPlayerList().getPlayerByName(name) == null)
					missing.add(name);
			try
			{
				agents = missing.isEmpty() ? null
					: AgentProcess.start(missing, port, settings.outDir());
			}catch(IOException e)
			{
				source.sendFailure(
					Component.literal("Can't start the bots: " + e));
				return 0;
			}
		}
		
		Vec3 center = source.getPosition();
		AABB box = arena(server).box(center);
		referee = new GymReferee(server, arena(server), box, settings, players,
			agents);
		source.sendSuccess(() -> Component
			.literal("SpearMace gym started on port " + port + ", seed " + seed
				+ (arena(server).hasBox() ? ""
					: ", arena 64x64 around you (set one with /gym arena)")
				+ ". Data in " + settings.outDir()),
			true);
		return 1;
	}
	
	private int stop(CommandContext<CommandSourceStack> c)
	{
		GymReferee r = referee();
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
		GymReferee r = referee();
		GymArena a = arena(c.getSource().getServer());
		String message = (r == null ? "Not running" : r.status()) + ". Arena: "
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
