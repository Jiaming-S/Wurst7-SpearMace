/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Starts gym bot clients and keeps them running.
 *
 * <p>
 * From a dedicated server, the client launch comes from
 * {@value #LAUNCH_FILE}, which {@code runSpearMaceGymServer} writes into
 * the server's run dir. From a client, the bots copy that client's own
 * launch. Either way only the role, the username and the game directory
 * differ.
 *
 * <p>
 * Without a display (Linux with no {@code DISPLAY}/{@code WAYLAND_DISPLAY}
 * or {@code WURST_GYM_HEADLESS=1}), the bots use SDL's offscreen video
 * driver and skip drawing the world.
 */
public final class AgentProcess
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("SpearMaceGym");
	public static final String LAUNCH_FILE = "gym-agent-launch.json";
	private static final int MAX_RESTARTS = 3;
	
	private final String address;
	private final Path logDir;
	private final LinkedHashMap<String, Process> processes =
		new LinkedHashMap<>();
	private final Map<String, Integer> restarts = new LinkedHashMap<>();
	private final Thread shutdownHook = new Thread(this::stop);
	
	private AgentProcess(String address, Path logDir)
	{
		this.address = address;
		this.logDir = logDir;
	}
	
	/**
	 * @param usernames
	 *            one client per name
	 * @param address
	 *            where the bots join, such as {@code localhost:25565}
	 */
	public static AgentProcess start(List<String> usernames, String address,
		Path logDir) throws IOException
	{
		AgentProcess agents = new AgentProcess(address, logDir);
		Runtime.getRuntime().addShutdownHook(agents.shutdownHook);
		Files.createDirectories(logDir);
		for(String name : usernames)
			agents.launch(name);
		return agents;
	}
	
	private void launch(String name) throws IOException
	{
		// next to this game's run dir
		Path cwd = Path.of("").toAbsolutePath();
		Path gameDir = cwd.resolveSibling(cwd.getFileName() + "-" + name);
		Files.createDirectories(gameDir);
		ProcessBuilder builder = new ProcessBuilder(command(name))
			.directory(gameDir.toFile()).redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect
				.appendTo(logDir.resolve("client-" + name + ".log").toFile()));
		if(headless() && isLinux())
			builder.environment().put("SDL_VIDEO_DRIVER", "offscreen");
		LOGGER.info("Starting bot client {} in {}", name, gameDir);
		processes.put(name, builder.start());
	}
	
	/**
	 * Starts bot clients that died again, up to {@value #MAX_RESTARTS}
	 * times each.
	 *
	 * @return the names of the restarted bots
	 */
	public List<String> restartDead()
	{
		ArrayList<String> restarted = new ArrayList<>();
		for(Map.Entry<String, Process> e : List.copyOf(processes.entrySet()))
		{
			String name = e.getKey();
			if(e.getValue().isAlive()
				|| restarts.getOrDefault(name, 0) >= MAX_RESTARTS)
				continue;
			
			restarts.merge(name, 1, Integer::sum);
			try
			{
				launch(name);
				restarted.add(name);
			}catch(IOException ex)
			{
				LOGGER.error("Can't restart bot client {}", name, ex);
			}
		}
		return restarted;
	}
	
	private List<String> command(String username) throws IOException
	{
		ArrayList<String> cmd = new ArrayList<>();
		Path launchFile = Path.of(LAUNCH_FILE);
		if(Files.exists(launchFile))
			fromLaunchFile(launchFile, cmd);
		else
			fromOwnLaunch(cmd);
		
		// JVM options, right after the java binary
		ArrayList<String> options = new ArrayList<>();
		// a launcher flag that macOS can't open the window without, which
		// the JVM's input arguments don't list
		if(System.getProperty("os.name").startsWith("Mac")
			&& !cmd.contains("-XstartOnFirstThread"))
			options.add("-XstartOnFirstThread");
		options.add("-Dwurst.spearmace.gym=agent");
		options.add("-Dwurst.spearmace.gym.host=" + address);
		if(headless())
			options.add("-Dwurst.spearmace.gym.headless=true");
		cmd.addAll(1, options);
		
		cmd.add("--username");
		cmd.add(username);
		return cmd;
	}
	
	/** java, JVM options, classpath, main class, program arguments. */
	private static void fromLaunchFile(Path file, List<String> cmd)
		throws IOException
	{
		JsonObject json = JsonParser
			.parseString(Files.readString(file, StandardCharsets.UTF_8))
			.getAsJsonObject();
		cmd.add(json.get("java").getAsString());
		for(JsonElement arg : json.getAsJsonArray("jvmArgs"))
			cmd.add(arg.getAsString());
		cmd.add("-cp");
		cmd.add(json.get("classpath").getAsString());
		cmd.add(json.get("mainClass").getAsString());
		for(JsonElement arg : json.getAsJsonArray("args"))
			if(!arg.getAsString().startsWith("--username"))
				cmd.add(arg.getAsString());
	}
	
	private static void fromOwnLaunch(List<String> cmd)
	{
		cmd.add(ProcessHandle.current().info().command().orElse(Path
			.of(System.getProperty("java.home"), "bin", "java").toString()));
		for(String arg : ManagementFactory.getRuntimeMXBean()
			.getInputArguments())
		{
			// a second debugger on the same port would fail to start
			if(arg.startsWith("-agentlib:jdwp")
				|| arg.startsWith("-Dwurst.spearmace.gym")
				|| arg.startsWith("-Dfabric.client.gametest"))
				continue;
			cmd.add(arg);
		}
		cmd.add("-cp");
		cmd.add(System.getProperty("java.class.path"));
		
		// main class, then the program arguments
		String[] launch = System.getProperty("sun.java.command").split(" ");
		cmd.add(launch[0]);
		List<String> args = Arrays.asList(launch).subList(1, launch.length);
		for(int i = 0; i < args.size(); i++)
		{
			String arg = args.get(i);
			if(arg.equals("--username") || arg.equals("--gameDir"))
				i++;
			else if(!arg.startsWith("--username=")
				&& !arg.startsWith("--gameDir="))
				cmd.add(arg);
		}
	}
	
	private static boolean headless()
	{
		if("1".equals(System.getenv("WURST_GYM_HEADLESS")))
			return true;
		return isLinux() && System.getenv("DISPLAY") == null
			&& System.getenv("WAYLAND_DISPLAY") == null;
	}
	
	private static boolean isLinux()
	{
		return System.getProperty("os.name").startsWith("Linux");
	}
	
	/**
	 * Ends every bot client this started, after giving them a moment to
	 * get the last {@code Stop} and close their files.
	 */
	public void stopSoon()
	{
		CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS)
			.execute(this::stop);
	}
	
	/** Ends every bot client this started. */
	public void stop()
	{
		processes.values().forEach(Process::destroy);
		processes.clear();
		try
		{
			Runtime.getRuntime().removeShutdownHook(shutdownHook);
		}catch(IllegalStateException e)
		{
			// already shutting down
		}
	}
}
