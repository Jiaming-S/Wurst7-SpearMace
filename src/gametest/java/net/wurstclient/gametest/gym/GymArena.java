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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where the gym fights and with what: a box that the bots must stay in,
 * optional start points, and optionally the kit to hand out. Saved in the
 * world folder, so an arena built once can be used again.
 */
public final class GymArena
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("SpearMaceGym");
	private static final String FILE = "spearmace-gym.json";
	
	/** How far apart sampled starts are. */
	private static final double MIN_START_DISTANCE = 6;
	private static final double MAX_START_DISTANCE = 40;
	
	/** The most blocks {@code /clone} copies at once. */
	private static final int CLONE_LIMIT = 32768;
	
	/** Hotbar slots, as in {@code SpearMaceTest}. */
	private static final String[] DEFAULT_KIT =
		{"hotbar.1 with netherite_spear[enchantments={\"minecraft:lunge\":3}]",
			"hotbar.2 with wind_charge 64", "hotbar.3 with ender_pearl 16",
			"hotbar.4 with mace[enchantments={\"minecraft:wind_burst\":3}]",
			"hotbar.5 with netherite_sword", "hotbar.6 with netherite_axe"};
	
	private static final String[] ARMOR =
		{"head with diamond_helmet", "chest with diamond_chestplate",
			"legs with diamond_leggings", "feet with diamond_boots"};
	private static final String PROTECTION =
		"[enchantments={\"minecraft:protection\":4}]";
	
	private BlockPos from;
	private BlockPos to;
	private final ArrayList<Vec3> spawns = new ArrayList<>();
	
	/** Every inventory slot, or {@code null} for the default kit. */
	private List<ItemStack> kit;
	
	public boolean hasBox()
	{
		return from != null;
	}
	
	public void setBox(BlockPos a, BlockPos b)
	{
		from = BlockPos.min(a, b);
		to = BlockPos.max(a, b);
	}
	
	public void clearBox()
	{
		from = null;
		to = null;
	}
	
	/**
	 * The box, or a 128x128 one around {@code center} if none is set, as
	 * pearl catches easily carry the bots 20 blocks past each other. It
	 * reaches far down, so that the floor is in it even if whoever started
	 * the gym was flying.
	 */
	public AABB box(Vec3 center)
	{
		if(from == null)
			return new AABB(center.x - 64, center.y - 64, center.z - 64,
				center.x + 64, center.y + 128, center.z + 64);
		return new AABB(from.getX(), from.getY(), from.getZ(), to.getX() + 1,
			to.getY() + 1, to.getZ() + 1);
	}
	
	public List<Vec3> spawns()
	{
		return spawns;
	}
	
	public boolean hasKit()
	{
		return kit != null;
	}
	
	/** Uses {@code player}'s whole inventory as the kit from now on. */
	public void setKit(ServerPlayer player)
	{
		Inventory inv = player.getInventory();
		ArrayList<ItemStack> stacks = new ArrayList<>();
		for(int i = 0; i < inv.getContainerSize(); i++)
			stacks.add(inv.getItem(i).copy());
		kit = stacks;
	}
	
	public void clearKit()
	{
		kit = null;
	}
	
	/**
	 * Replaces {@code player}'s inventory with the kit. The default kit
	 * gets {@code apples} golden apples, and {@code armor} adds diamond
	 * armor with Protection IV to either kit.
	 */
	public void giveKit(MinecraftServer server, ServerPlayer player, int apples,
		boolean armor)
	{
		Inventory inv = player.getInventory();
		inv.clearContent();
		String uuid = player.getStringUUID();
		if(kit != null)
			for(int i = 0; i < kit.size() && i < inv.getContainerSize(); i++)
				inv.setItem(i, kit.get(i).copy());
		else
		{
			for(String item : DEFAULT_KIT)
				GymReferee.command(server,
					"item replace entity " + uuid + " " + item);
			if(apples > 0)
				GymReferee.command(server, "item replace entity " + uuid
					+ " hotbar.7 with golden_apple " + apples);
		}
		if(armor)
			for(String piece : ARMOR)
				GymReferee.command(server, "item replace entity " + uuid
					+ " armor." + piece + PROTECTION);
		inv.setSelectedSlot(0);
	}
	
	/**
	 * Two start points: random distinct spawns if there are two or more,
	 * otherwise two floor spots in the box, a random distance apart.
	 *
	 * @param box
	 *            the arena, already moved by {@code offset}
	 * @param offset
	 *            how far this copy of the arena is from the original,
	 *            which the spawns are for
	 * @return {@code null} if no two spots fit
	 */
	public Vec3[] sampleStarts(ServerLevel level, AABB box, Vec3 offset,
		Random random)
	{
		if(spawns.size() >= 2)
		{
			int a = random.nextInt(spawns.size());
			int b = random.nextInt(spawns.size() - 1);
			if(b >= a)
				b++;
			return new Vec3[]{spawns.get(a).add(offset),
				spawns.get(b).add(offset)};
		}
		
		for(int attempt = 0; attempt < 100; attempt++)
		{
			double angle = random.nextDouble() * 2 * Math.PI;
			double distance = MIN_START_DISTANCE + random.nextDouble()
				* (MAX_START_DISTANCE - MIN_START_DISTANCE);
			double dx = Math.cos(angle) * distance / 2;
			double dz = Math.sin(angle) * distance / 2;
			if(box.getXsize() < 2 * Math.abs(dx)
				|| box.getZsize() < 2 * Math.abs(dz))
				continue;
			
			double cx = box.minX + Math.abs(dx)
				+ random.nextDouble() * (box.getXsize() - 2 * Math.abs(dx));
			double cz = box.minZ + Math.abs(dz)
				+ random.nextDouble() * (box.getZsize() - 2 * Math.abs(dz));
			
			Vec3 a = floor(level, box, cx - dx, cz - dz);
			Vec3 b = floor(level, box, cx + dx, cz + dz);
			if(a != null && b != null)
				return new Vec3[]{a, b};
		}
		return null;
	}
	
	/**
	 * The lowest spot in the box's column at x/z where a player can stand,
	 * so that a roofed arena starts on its floor.
	 */
	private static Vec3 floor(ServerLevel level, AABB box, double x, double z)
	{
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minY = (int)Math.floor(box.minY);
		int maxY = (int)Math.ceil(box.maxY) - 2;
		for(int y = minY; y < maxY; y++)
		{
			pos.set(Math.floor(x), y, Math.floor(z));
			if(!level.getBlockState(pos).isSolid()
				|| !level.getBlockState(pos.above()).isAir()
				|| !level.getBlockState(pos.above(2)).isAir())
				continue;
			return new Vec3(Math.floor(x) + 0.5, y + 1, Math.floor(z) + 0.5);
		}
		return null;
	}
	
	/**
	 * Copies the blocks of {@code box} by {@code offset}, in pieces small
	 * enough for {@code /clone}. The chunks on both ends must be loaded.
	 */
	public void copyTo(MinecraftServer server, AABB box, Vec3 offset)
	{
		int minX = (int)Math.floor(box.minX);
		int minY = (int)Math.floor(box.minY);
		int minZ = (int)Math.floor(box.minZ);
		int maxX = (int)Math.ceil(box.maxX) - 1;
		int maxY = (int)Math.ceil(box.maxY) - 1;
		int maxZ = (int)Math.ceil(box.maxZ) - 1;
		int depth = maxZ - minZ + 1;
		int height = maxY - minY + 1;
		
		// strips along x, as wide as fits a full-height slab
		int stripWidth = Math.max(1, CLONE_LIMIT / (depth * height));
		int slabHeight = Math.max(1, CLONE_LIMIT / (depth * stripWidth));
		for(int x = minX; x <= maxX; x += stripWidth)
			for(int y = minY; y <= maxY; y += slabHeight)
			{
				int x2 = Math.min(maxX, x + stripWidth - 1);
				int y2 = Math.min(maxY, y + slabHeight - 1);
				GymReferee.command(server,
					String.format("clone %d %d %d %d %d %d %d %d %d", x, y,
						minZ, x2, y2, maxZ, x + (int)offset.x,
						y + (int)offset.y, minZ + (int)offset.z));
			}
	}
	
	public static GymArena load(MinecraftServer server)
	{
		GymArena arena = new GymArena();
		Path file = server.getWorldPath(LevelResource.ROOT).resolve(FILE);
		if(!Files.exists(file))
			return arena;
		
		try
		{
			JsonObject json = JsonParser
				.parseString(Files.readString(file, StandardCharsets.UTF_8))
				.getAsJsonObject();
			if(json.has("from"))
				arena.setBox(blockPos(json.getAsJsonArray("from")),
					blockPos(json.getAsJsonArray("to")));
			for(JsonElement e : json.getAsJsonArray("spawns"))
			{
				JsonArray a = e.getAsJsonArray();
				arena.spawns.add(new Vec3(a.get(0).getAsDouble(),
					a.get(1).getAsDouble(), a.get(2).getAsDouble()));
			}
			if(json.has("kit"))
			{
				RegistryOps<JsonElement> ops = server.registryAccess()
					.createSerializationContext(JsonOps.INSTANCE);
				ArrayList<ItemStack> kit = new ArrayList<>();
				for(JsonElement e : json.getAsJsonArray("kit"))
					kit.add(ItemStack.OPTIONAL_CODEC.parse(ops, e)
						.resultOrPartial(LOGGER::warn).orElse(ItemStack.EMPTY));
				arena.kit = kit;
			}
		}catch(IOException | RuntimeException e)
		{
			LOGGER.error("Can't read {}, starting over", file, e);
			return new GymArena();
		}
		return arena;
	}
	
	public void save(MinecraftServer server)
	{
		JsonObject json = new JsonObject();
		if(from != null)
		{
			json.add("from", array(from));
			json.add("to", array(to));
		}
		JsonArray spawnArray = new JsonArray();
		spawns.forEach(s -> spawnArray.add(GymJson.vec(s)));
		json.add("spawns", spawnArray);
		if(kit != null)
		{
			RegistryOps<JsonElement> ops = server.registryAccess()
				.createSerializationContext(JsonOps.INSTANCE);
			JsonArray kitArray = new JsonArray();
			for(ItemStack stack : kit)
				kitArray.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack)
					.resultOrPartial(LOGGER::warn).orElse(new JsonObject()));
			json.add("kit", kitArray);
		}
		
		Path file = server.getWorldPath(LevelResource.ROOT).resolve(FILE);
		try
		{
			Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
		}catch(IOException e)
		{
			LOGGER.error("Can't save {}", file, e);
		}
	}
	
	private static JsonArray array(BlockPos pos)
	{
		JsonArray array = new JsonArray();
		array.add(pos.getX());
		array.add(pos.getY());
		array.add(pos.getZ());
		return array;
	}
	
	private static BlockPos blockPos(JsonArray a)
	{
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(),
			a.get(2).getAsInt());
	}
}
