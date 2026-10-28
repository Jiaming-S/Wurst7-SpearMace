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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts gym bot clients as copies of this client's own launch, which
 * already has the right classpath, natives and dev launcher settings. Only
 * the role, the username and the game directory differ.
 */
public final class AgentProcess
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("SpearMaceGym");
	
	private final ArrayList<Process> processes = new ArrayList<>();
	private final Thread shutdownHook = new Thread(this::stop);
	
	/**
	 * @param usernames
	 *            one client per name
	 * @param port
	 *            where the bots join
	 */
	public static AgentProcess start(List<String> usernames, int port,
		Path logDir) throws IOException
	{
		AgentProcess agents = new AgentProcess();
		Runtime.getRuntime().addShutdownHook(agents.shutdownHook);
		Files.createDirectories(logDir);
		for(String name : usernames)
		{
			// next to this client's run dir
			Path gameDir = Path.of("").toAbsolutePath().resolveSibling(
				Path.of("").toAbsolutePath().getFileName() + "-" + name);
			Files.createDirectories(gameDir);
			ProcessBuilder builder = new ProcessBuilder(command(name, port))
				.directory(gameDir.toFile()).redirectErrorStream(true)
				.redirectOutput(
					logDir.resolve("client-" + name + ".log").toFile());
			LOGGER.info("Starting bot client {} in {}: {}", name, gameDir,
				builder.command());
			agents.processes.add(builder.start());
		}
		return agents;
	}
	
	private static List<String> command(String username, int port)
	{
		String java = ProcessHandle.current().info().command().orElse(
			Path.of(System.getProperty("java.home"), "bin", "java").toString());
		ArrayList<String> cmd = new ArrayList<>();
		cmd.add(java);
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
		// a launcher flag, so the input arguments don't list it, but macOS
		// can't open the window without it
		if(System.getProperty("os.name").startsWith("Mac"))
			cmd.add("-XstartOnFirstThread");
		cmd.add("-Dwurst.spearmace.gym=agent");
		cmd.add("-Dwurst.spearmace.gym.port=" + port);
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
		cmd.add("--username");
		cmd.add(username);
		return cmd;
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
		for(Process p : processes)
			p.destroy();
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
