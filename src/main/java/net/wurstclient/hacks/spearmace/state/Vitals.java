/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.state;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.wurstclient.WurstClient;

/**
 * The player's health and the effects that change it, as the client shows
 * them.
 *
 * @param regenTicks
 *            ticks of Regeneration left, 0 if none
 * @param regenAmp
 *            its amplifier
 * @param resistanceAmp
 *            the Resistance amplifier, or -1 if none
 */
public record Vitals(float health, float maxHealth, float absorption,
	int regenTicks, int regenAmp, int resistanceAmp)
{
	private static final Minecraft MC = WurstClient.MC;
	
	/** {@code RegenerationMobEffect}: heals 1 every this many ticks. */
	private static final int REGEN_INTERVAL = 50;
	
	public static Vitals capture()
	{
		MobEffectInstance regen = MC.player.getEffect(MobEffects.REGENERATION);
		return new Vitals(MC.player.getHealth(), MC.player.getMaxHealth(),
			MC.player.getAbsorptionAmount(),
			regen == null ? 0 : regen.getDuration(),
			regen == null ? 0 : regen.getAmplifier(),
			amplifier(MobEffects.RESISTANCE));
	}
	
	private static int amplifier(Holder<MobEffect> effect)
	{
		MobEffectInstance instance = MC.player.getEffect(effect);
		return instance == null ? -1 : instance.getAmplifier();
	}
	
	/** One tick later: Regeneration heals and counts down. */
	public Vitals ticked()
	{
		if(regenTicks <= 0)
			return this;
		
		// MobEffectInstance.tick(), RegenerationMobEffect
		int interval = REGEN_INTERVAL >> regenAmp;
		float healed = health;
		if((interval <= 0 || regenTicks % interval == 0) && health < maxHealth)
			healed = Math.min(maxHealth, health + 1);
		return new Vitals(healed, maxHealth, absorption, regenTicks - 1,
			regenAmp, resistanceAmp);
	}
	
	/**
	 * After taking {@code damage} that Resistance reduces, such as fall
	 * damage. Absorption takes it first.
	 */
	public Vitals hurt(double damage)
	{
		// LivingEntity.getDamageAfterMagicAbsorb()
		if(resistanceAmp >= 0)
			damage = Math.max(0, damage * (25 - (resistanceAmp + 1) * 5) / 25);
		
		float fromAbsorption = (float)Math.min(absorption, damage);
		float left = (float)Math.max(0, health - (damage - fromAbsorption));
		return new Vitals(left, maxHealth, absorption - fromAbsorption,
			regenTicks, regenAmp, resistanceAmp);
	}
	
	/** After the effects of a golden apple, see {@link Apple}. */
	public Vitals ate(Apple apple)
	{
		// AbsorptionMobEffect.onEffectStarted() on every apple
		float absorbed = Math.max(absorption, apple.absorption());
		
		// MobEffectInstance.update(): the longer or stronger one wins
		boolean regen = apple.regenAmp() > regenAmp
			|| apple.regenAmp() == regenAmp && apple.regenTicks() > regenTicks
			|| regenTicks <= 0;
		return new Vitals(health, maxHealth, absorbed,
			regen ? apple.regenTicks() : regenTicks,
			regen ? apple.regenAmp() : regenAmp,
			Math.max(resistanceAmp, apple.resistanceAmp()));
	}
	
	/** Health the Regeneration still heals, up to the maximum. */
	public double regenLeft()
	{
		if(regenTicks <= 0)
			return 0;
		
		int interval = REGEN_INTERVAL >> regenAmp;
		int heals = interval <= 0 ? regenTicks : regenTicks / interval;
		return Math.min(heals, maxHealth - health);
	}
	
	/** Health plus absorption plus what Regeneration still heals. */
	public double effective()
	{
		return health + absorption + regenLeft();
	}
	
	/** What eating each kind of golden apple does. */
	public enum Apple
	{
		/** {@code Consumables.GOLDEN_APPLE} */
		GOLDEN(100, 1, 4, -1),
		
		/** {@code Consumables.ENCHANTED_GOLDEN_APPLE} */
		ENCHANTED(400, 1, 16, 0);
		
		/** {@code Consumable.consumeSeconds}, 1.6 seconds. */
		public static final int EAT_TICKS = 32;
		
		private final int regenTicks;
		private final int regenAmp;
		private final float absorption;
		private final int resistanceAmp;
		
		private Apple(int regenTicks, int regenAmp, float absorption,
			int resistanceAmp)
		{
			this.regenTicks = regenTicks;
			this.regenAmp = regenAmp;
			this.absorption = absorption;
			this.resistanceAmp = resistanceAmp;
		}
		
		public int regenTicks()
		{
			return regenTicks;
		}
		
		public int regenAmp()
		{
			return regenAmp;
		}
		
		public float absorption()
		{
			return absorption;
		}
		
		public int resistanceAmp()
		{
			return resistanceAmp;
		}
		
		/** Health this apple adds, as {@link Vitals#effective()} counts it. */
		public double value(Vitals v)
		{
			return v.ate(this).effective() - v.effective();
		}
	}
}
