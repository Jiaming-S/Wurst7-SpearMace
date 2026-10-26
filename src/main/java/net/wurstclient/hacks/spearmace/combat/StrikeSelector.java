/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.combat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.wurstclient.hacks.spearmace.combat.Loadout.PreItem;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.sim.StrikeModel;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.state.SelfState;

/**
 * Picks how to hit a target while falling:
 * <ul>
 * <li>shield raised toward us: {@link StunSlam}</li>
 * <li>above the target: spear, sword or hand into the mace, whichever is
 * the first that can be fully charged before contact</li>
 * <li>below the target: spear into the mace if the spear reaches it,
 * otherwise {@link EscapeLunge}</li>
 * </ul>
 */
public enum StrikeSelector
{
	;
	
	/** Kept between the predicted fall distance and the smash threshold. */
	private static final double FALL_SAFETY = 0.1;
	
	public enum Kind
	{
		SPEARMACE,
		SWORDMACE,
		HANDMACE,
		STUNSLAM,
		ESCAPE_LUNGE,
		NONE;
	}
	
	/**
	 * @param option
	 *            the planned hit, or {@code null} for a stunslam, lunge or
	 *            nothing
	 */
	public record Decision(Kind kind, Action action, StrikeOption option,
		String reason)
	{
		@Override
		public String toString()
		{
			return kind + " (" + reason + ")"
				+ (option == null ? "" : ": " + option);
		}
	}
	
	public static Decision decide(Engagement e)
	{
		Kit kit = e.state().self().kit();
		if(kit.mace() < 0)
			return none("no mace");
		
		if(e.landing() == 0)
			return none("on the ground");
		
		if(e.shieldFacingUs(0))
		{
			if(kit.axe() < 0)
				return none("shield raised and no axe");
			return new Decision(Kind.STUNSLAM, new StunSlam(), null,
				"shield raised toward us");
		}
		
		StrikeOption spear = plan(e, PreItem.SPEAR);
		boolean above = e.eye(0).y > e.state().target().eyePos().y;
		if(above)
		{
			if(spear != null && spear.charged())
				return strike(Kind.SPEARMACE, spear,
					"spear charges before contact");
			
			StrikeOption sword = plan(e, PreItem.SWORD);
			if(sword != null && sword.charged())
				return strike(Kind.SWORDMACE, sword,
					"sword charges before contact");
			
			StrikeOption hand = plan(e, PreItem.HAND);
			if(hand != null)
				return strike(Kind.HANDMACE, hand,
					"no time to charge a weapon");
			
			if(spear != null)
				return strike(Kind.SPEARMACE, spear, "only the spear reaches");
			
			return none("no contact before landing");
		}
		
		if(spear != null)
			return strike(Kind.SPEARMACE, spear,
				"below the target, but the spear reaches");
		
		if(kit.lungeSpear() >= 0 && kit.hand() >= 0)
			return new Decision(Kind.ESCAPE_LUNGE, new EscapeLunge(), null,
				"below the target and out of reach");
		
		return none("below the target, out of reach and no Lunge spear");
	}
	
	/** Every pre-item that could hit, for planners with their own policy. */
	public static List<StrikeOption> options(Engagement e)
	{
		ArrayList<StrikeOption> options = new ArrayList<>();
		for(PreItem pre : PreItem.values())
		{
			StrikeOption option = plan(e, pre);
			if(option != null)
				options.add(option);
		}
		return options;
	}
	
	/**
	 * When to swap from {@code pre} to the mace: at the first update where
	 * the pick finds the target and the charge is full, or at the last
	 * update before the target leaves reach if it never gets full.
	 *
	 * @return the plan, or {@code null} if there's no such pre-item or no
	 *         contact before landing
	 */
	public static StrikeOption plan(Engagement e, PreItem pre)
	{
		SelfState self = e.state().self();
		Kit kit = self.kit();
		int slot = kit.slotFor(pre);
		int maceSlot = kit.mace();
		if(slot < 0 || maceSlot < 0)
			return null;
		
		ItemStack stack = kit.get(slot);
		ItemStack mace = kit.get(maceSlot);
		double delay = kit.attackDelay(stack);
		boolean held = kit.selected() == slot;
		
		// the switch happens in the next tick's handleKeybinds(), and that
		// tick's player tick resets the charge after the update has run
		int first = held ? 0 : 2;
		for(int k = first; k + 1 < Math.min(e.landing(),
			Engagement.HORIZON); k++)
		{
			int i = k + 1;
			if(!e.inReach(stack, i)
				|| !StrikeModel.canSmash(e.fall(i) - FALL_SAFETY))
				continue;
				
			// the attack charge in this update, before this tick's increment,
			// which is also what the server sees, as its own charge is a
			// tick behind (measured)
			double ticker = held ? self.attackStrengthTicker() + k : k - 2;
			double strength = StrikeModel.strength(ticker, delay);
			boolean charged = StrikeModel.isFull(strength);
			boolean lastChance = !e.inReach(stack, i + 1);
			if(!charged && !lastChance)
				continue;
				
			// in the tick that lands, the server already counts the player
			// as on the ground but hasn't reset the fall distance yet, so
			// the hit still smashes but isn't a crit (measured)
			boolean crit = charged && !self.sprinting() && e.fall(i) > 0
				&& i + 1 < e.landing();
			double damage =
				StrikeModel.damage(kit.attackDamage(stack), strength, e.fall(i),
					Loadout.level(mace, Enchantments.DENSITY), crit);
			return new StrikeOption(pre, slot, k, charged, crit, strength,
				e.fall(i), damage, e.distance(i));
		}
		
		return null;
	}
	
	private static Decision strike(Kind kind, StrikeOption option,
		String reason)
	{
		return new Decision(kind, new MaceStrike(option.pre()), option, reason);
	}
	
	private static Decision none(String reason)
	{
		return new Decision(Kind.NONE, null, null, reason);
	}
}
