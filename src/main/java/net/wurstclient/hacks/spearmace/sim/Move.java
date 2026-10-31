/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.sim;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;

/**
 * The held movement keys as {@code KeyboardInput} turns them into a move
 * vector: 1 per key, -1 for the opposite one, normalized so that diagonals
 * aren't longer.
 */
public record Move(float left, float forward)
{
	private static final Minecraft MC = WurstClient.MC;
	
	/** {@code UseEffects.DEFAULT.speedMultiplier()} */
	private static final float ITEM_USE_SPEED = 0.2F;
	
	public static final Move NONE = new Move(0, 0);
	public static final Move FORWARD = new Move(0, 1);
	
	public static Move ofKeys(boolean up, boolean down, boolean left,
		boolean right)
	{
		Vec2 v = new Vec2(impulse(left, right), impulse(up, down)).normalized();
		return new Move(v.x, v.y);
	}
	
	/** The movement keys held right now. */
	public static Move ofHeldKeys()
	{
		return ofKeys(MC.options.keyUp.isDown(), MC.options.keyDown.isDown(),
			MC.options.keyLeft.isDown(), MC.options.keyRight.isDown());
	}
	
	private static float impulse(boolean positive, boolean negative)
	{
		return (positive ? 1 : 0) - (negative ? 1 : 0);
	}
	
	/** {@code ClientInput.hasForwardImpulse()}, which sprinting needs. */
	public boolean hasForwardImpulse()
	{
		return forward > 1.0E-5F;
	}
	
	public boolean isMoving()
	{
		return left != 0 || forward != 0;
	}
	
	/** Without an item in use. */
	public Vec3 acceleration(float speed, float yaw)
	{
		return acceleration(speed, yaw, false);
	}
	
	/**
	 * The velocity this input adds in one tick: {@code LocalPlayer
	 * .modifyInput()} (without sneaking), then
	 * {@code Entity.getInputVector()}.
	 *
	 * @param usingItem
	 *            whether an item is in use, which slows the keys down to
	 *            {@code UseEffects.DEFAULT}'s speed multiplier
	 */
	public Vec3 acceleration(float speed, float yaw, boolean usingItem)
	{
		if(!isMoving())
			return Vec3.ZERO;
		
		Vec2 input = new Vec2(left, forward).scale(0.98F);
		if(usingItem)
			input = input.scale(ITEM_USE_SPEED);
		input = squareMovement(input);
		Vec3 movement = new Vec3(input.x, 0, input.y);
		if(movement.lengthSqr() > 1)
			movement = movement.normalize();
		movement = movement.scale(speed);
		
		float sin = Mth.sin(yaw * Mth.DEG_TO_RAD);
		float cos = Mth.cos(yaw * Mth.DEG_TO_RAD);
		return new Vec3(movement.x * cos - movement.z * sin, 0,
			movement.z * cos + movement.x * sin);
	}
	
	/** {@code LocalPlayer.modifyInputSpeedForSquareMovement()} */
	private static Vec2 squareMovement(Vec2 input)
	{
		float length = input.length();
		if(length <= 0)
			return input;
		
		Vec2 direction = input.scale(1 / length);
		float x = Math.abs(direction.x);
		float y = Math.abs(direction.y);
		float tan = y > x ? x / y : y / x;
		float toSquare = Mth.sqrt(1 + Mth.square(tan));
		return direction.scale(Math.min(length * toSquare, 1));
	}
	
	@Override
	public String toString()
	{
		return String.format("(%.2f, %.2f)", left, forward);
	}
}
