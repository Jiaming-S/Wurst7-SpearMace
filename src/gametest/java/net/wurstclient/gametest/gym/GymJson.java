/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Turns the planner's records into JSON by their components, so that new
 * fields show up in the data without touching this class. Game objects
 * that aren't records become something short: vectors {@code [x,y,z]},
 * item stacks {@code "id xcount"}, entities their id.
 */
public enum GymJson
{
	;
	
	private static final Map<Class<?>, Function<Object, JsonElement>> CUSTOM =
		new ConcurrentHashMap<>();
	
	/**
	 * Writes values of exactly {@code type} with {@code writer}, for classes
	 * that only the client has, such as Wurst's.
	 */
	public static <T> void register(Class<T> type,
		Function<? super T, JsonElement> writer)
	{
		CUSTOM.put(type, value -> writer.apply(type.cast(value)));
	}
	
	public static JsonElement of(Object value)
	{
		Function<Object, JsonElement> custom =
			value == null ? null : CUSTOM.get(value.getClass());
		if(custom != null)
			return custom.apply(value);
		
		return switch(value)
		{
			case null -> JsonNull.INSTANCE;
			case Boolean b -> new JsonPrimitive(b);
			case Number n -> number(n);
			case Character c -> new JsonPrimitive(c);
			case String s -> new JsonPrimitive(s);
			case Enum<?> e -> new JsonPrimitive(e.name());
			case Vec3 v -> vec(v);
			case ItemStack stack -> new JsonPrimitive(item(stack));
			case Entity entity -> new JsonPrimitive(entity.getId());
			case Record r -> record(r);
			case Collection<?> c -> array(c);
			case Map<?, ?> m -> map(m);
			default -> new JsonPrimitive(value.toString());
		};
	}
	
	private static JsonElement number(Number n)
	{
		double d = n.doubleValue();
		if(Double.isNaN(d) || Double.isInfinite(d))
			return JsonNull.INSTANCE;
		return new JsonPrimitive(n);
	}
	
	public static JsonArray vec(Vec3 v)
	{
		JsonArray array = new JsonArray();
		array.add(v.x);
		array.add(v.y);
		array.add(v.z);
		return array;
	}
	
	public static String item(ItemStack stack)
	{
		if(stack.isEmpty())
			return "empty";
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x"
			+ stack.getCount();
	}
	
	private static JsonObject record(Record r)
	{
		JsonObject json = new JsonObject();
		// tells apart the cases of sealed interfaces like Pending
		if(r.getClass().getInterfaces().length > 0)
			json.addProperty("type", r.getClass().getSimpleName());
		for(RecordComponent c : r.getClass().getRecordComponents())
			try
			{
				json.add(c.getName(), of(c.getAccessor().invoke(r)));
			}catch(IllegalAccessException | InvocationTargetException e)
			{
				json.addProperty(c.getName(), e.toString());
			}
		return json;
	}
	
	private static JsonArray array(Collection<?> c)
	{
		JsonArray array = new JsonArray();
		for(Object o : c)
			array.add(of(o));
		return array;
	}
	
	private static JsonObject map(Map<?, ?> m)
	{
		JsonObject json = new JsonObject();
		m.forEach((k, v) -> json.add(String.valueOf(k), of(v)));
		return json;
	}
}
