/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** What the referee tells the bots. */
public enum GymPayloads
{
	;
	
	/**
	 * Fight {@code opponent} with {@code config} until {@link Stop}.
	 *
	 * @param side
	 *            {@code A} or {@code B}, which names the bot's data file
	 * @param config
	 *            the acting {@link GymConfig} as JSON
	 * @param expert
	 *            the expert's {@link GymConfig} as JSON, the same as
	 *            {@code config} if the bot acts as the expert, or empty for
	 *            none
	 * @param explore
	 *            how often to play a random action instead of the plan
	 * @param seed
	 *            for exploration
	 * @param outDir
	 *            where to write the data, on this same machine
	 */
	public record Start(int episode, String side, String opponent,
		String config, String expert, double explore, long seed, String outDir)
		implements CustomPacketPayload
	{
		public static final Type<Start> TYPE = new Type<>(id("gym_start"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Start> CODEC =
			StreamCodec.composite(ByteBufCodecs.VAR_INT, Start::episode,
				ByteBufCodecs.STRING_UTF8, Start::side,
				ByteBufCodecs.STRING_UTF8, Start::opponent,
				ByteBufCodecs.STRING_UTF8, Start::config,
				ByteBufCodecs.STRING_UTF8, Start::expert, ByteBufCodecs.DOUBLE,
				Start::explore, ByteBufCodecs.VAR_LONG, Start::seed,
				ByteBufCodecs.STRING_UTF8, Start::outDir, Start::new);
		
		@Override
		public Type<Start> type()
		{
			return TYPE;
		}
	}
	
	/** The episode is over: stop fighting and let go of every key. */
	public record Stop(int episode, String reason)
		implements CustomPacketPayload
	{
		public static final Type<Stop> TYPE = new Type<>(id("gym_stop"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Stop> CODEC =
			StreamCodec.composite(ByteBufCodecs.VAR_INT, Stop::episode,
				ByteBufCodecs.STRING_UTF8, Stop::reason, Stop::new);
		
		@Override
		public Type<Stop> type()
		{
			return TYPE;
		}
	}
	
	private static Identifier id(String path)
	{
		return Identifier.fromNamespaceAndPath("wurst_testmod", path);
	}
	
	public static void register()
	{
		PayloadTypeRegistry.clientboundPlay().register(Start.TYPE, Start.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Stop.TYPE, Stop.CODEC);
	}
}
