/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.maneuver.Maneuver;
import net.wurstclient.hacks.spearmace.maneuver.Prediction;
import net.wurstclient.hacks.spearmace.maneuver.SpearLunge;

/**
 * A flat {@link SpearLunge} straight away from a target, for when it falls
 * onto us from above and the spear can't reach it.
 */
public final class EscapeLunge implements Maneuver
{
	private static final Minecraft MC = WurstClient.MC;
	
	private final LivingEntity target;
	private SpearLunge lunge;
	
	public EscapeLunge(LivingEntity target)
	{
		this.target = target;
	}
	
	@Override
	public String name()
	{
		return "EscapeLunge(" + lunge().name() + ")";
	}
	
	/** The yaw that faces away from the target, horizontally. */
	public static float awayYaw(LivingEntity target)
	{
		Vec3 away = MC.player.position().subtract(target.position());
		if(away.horizontalDistanceSqr() < 1e-4)
			return MC.player.getYRot();
		
		return (float)Math.toDegrees(Math.atan2(away.z, away.x)) - 90F;
	}
	
	@Override
	public Prediction predict()
	{
		return lunge().predict();
	}
	
	@Override
	public Status tick(long tick)
	{
		return lunge().tick(tick);
	}
	
	@Override
	public String failureReason()
	{
		return lunge().failureReason();
	}
	
	/** The lunge, aimed when this is first used. */
	public SpearLunge lunge()
	{
		if(lunge == null)
			lunge = new SpearLunge(awayYaw(target), 0);
		return lunge;
	}
}
