/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.Level;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Hold;
import net.wurstclient.hacks.spearmace.combat.Engagement;
import net.wurstclient.hacks.spearmace.combat.EscapeLunge;
import net.wurstclient.hacks.spearmace.combat.Loadout.PreItem;
import net.wurstclient.hacks.spearmace.combat.MaceStrike;
import net.wurstclient.hacks.spearmace.combat.StrikeSelector;
import net.wurstclient.hacks.spearmace.combat.StrikeSelector.Decision;
import net.wurstclient.hacks.spearmace.combat.StunSlam;
import net.wurstclient.hacks.spearmace.maneuver.Cushion;
import net.wurstclient.hacks.spearmace.maneuver.Drift;
import net.wurstclient.hacks.spearmace.maneuver.Eat;
import net.wurstclient.hacks.spearmace.maneuver.Escape;
import net.wurstclient.hacks.spearmace.maneuver.PearlCatch;
import net.wurstclient.hacks.spearmace.maneuver.SpearLunge;
import net.wurstclient.hacks.spearmace.maneuver.WindChargeJump;
import net.wurstclient.hacks.spearmace.plan.eval.DamageRace;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.util.Rotation;

/**
 * The actions worth trying from a state, in the order the rules prefer
 * them:
 * <ul>
 * <li>below the target: a pearl catch straight up, to get above it
 * again</li>
 * <li>far from the target: on the ground, diagonal pearl catches toward
 * it (only when very far, they miss more often than straight up) and a
 * pearl catch straight up; a lunge toward it, or a launch for height,
 * running forward or straight up</li>
 * <li>in the air: drift toward the target</li>
 * <li>rising: wait for the apex, or lunge away from a near player, so that
 * it can't hit back after a smash</li>
 * <li>on the ground near the target: a pearl catch straight up, or a
 * launch with a wind charge</li>
 * <li>falling near the target: {@link StrikeSelector}'s pick, then the
 * other strikes, then a pearl catch straight up if the fall is still
 * short</li>
 * <li>falling anywhere: a {@link Cushion} if the landing would hurt</li>
 * </ul>
 * Golden apples:
 * <ul>
 * <li>low on health on the ground close to the target: first an
 * {@link Escape} away from it</li>
 * <li>vertical pearl catches eat while they wait, if an apple is worth
 * it</li>
 * <li>on the ground far from the target without absorption: first an
 * {@link Eat} (pre-gapping)</li>
 * <li>rising or falling: an {@link Eat}, first if health is low</li>
 * </ul>
 * Every list ends with a {@link Hold}, which always works.
 */
public final class Options
{
	/**
	 * Closer than this horizontally, the target is struck from above;
	 * further away, it has to be approached first.
	 */
	private static final double LAUNCH_RANGE = 8;
	
	/** Faster than gravity leaves a player standing still. */
	private static final double RISING = 0.1;
	
	/**
	 * Further than this horizontally, diagonal pearl catches are worth
	 * their misses; they cover about 17 (steep) to 22 (diagonal) blocks.
	 * Closer, a catch straight up and then lunges get there more reliably.
	 */
	private static final double DIAGONAL_RANGE = 20;
	
	/**
	 * Diagonal for distance, steep for height. Only thrown from the
	 * ground, where they miss the least.
	 */
	private static final float[] CATCH_PITCHES = {-30, -45};
	private static final int CATCH_DELAY = 3;
	
	/**
	 * Straight up. Longer delays catch only a little higher and miss more
	 * often, so this uses the approach's delay too.
	 */
	private static final float VERTICAL_PITCH = -90;
	
	/**
	 * Up to this height above the target's eyes, more height adds a lot of
	 * smash damage (4 per block for 3 blocks, then 2 for 5 blocks); above
	 * it, only 1 per block.
	 */
	private static final double LOW = 8;
	
	/**
	 * Target feet higher than the player's feet by more than this means
	 * the player has to get above it again first.
	 */
	private static final double ABOVE = 2;
	
	private static final int HOLD = 10;
	private static final int HOLD_RISING = 40;
	
	/** Less effective health than this close to the target is an emergency. */
	private static final double EMERGENCY_HEALTH = 8;
	
	public List<Action> of(Level level, GameState s)
	{
		ArrayList<Action> actions = new ArrayList<>();
		SelfState self = s.self();
		boolean far = s.targetAlive() && horizontalDistance(s) > LAUNCH_RANGE;
		boolean eat = Eat.worthEating(self);
		boolean lowHealth = self.expectedHealth() < DamageRace.LOW_HEALTH;
		if(s.targetAlive() && inDanger(s))
			actions.add(new Escape(towardYaw(s) + 180));
		if(s.targetAlive() && below(s) && !(self.vel().y > RISING))
			addVerticalCatch(s, actions);
		if(!s.targetAlive())
			actions.add(new Hold("no target", HOLD));
		else if(self.vel().y > RISING)
		{
			addIfNew(actions, new Eat(), eat && lowHealth);
			// right after a launch the player is still on the ground
			// near the target, the rules wait for the apex
			if(far)
				addApproach(s, actions, false);
			Hold hold = new Hold("rising", HOLD_RISING);
			Drift drift = new Drift(HOLD_RISING);
			actions.addAll(far ? List.of(drift, hold) : List.of(hold, drift));
			// right after a smash launch, so that a player doesn't hit back
			// when this comes down next to it again
			addIfNew(actions, new EscapeLunge(), !far && s.target().player()
				&& self.kit().lungeSpear() >= 0 && self.kit().hand() >= 0);
			addIfNew(actions, new Eat(), eat);
		}else if(self.onGround())
		{
			addIfNew(actions, new Eat(),
				far && eat && self.vitals().absorption() <= 0);
			if(far)
				addApproach(s, actions, true);
			else
			{
				addVerticalCatch(s, actions);
				if(self.kit().windCharge() >= 0)
					actions.add(WindChargeJump.atFeet(false));
			}
			actions.add(new Hold("on the ground", HOLD));
		}else
		{
			if(far)
				addApproach(s, actions, false);
			else
			{
				addStrikes(level, s, actions);
				if(height(s) < LOW)
					addVerticalCatch(s, actions);
			}
			addIfNew(actions, new Eat(), eat);
			addIfNew(actions, new Cushion(), self.kit().hasWindCharge()
				&& Cushion.landingDamage(level, self) > 0);
			actions.add(new Drift(HOLD));
			actions.add(new Hold("falling", HOLD));
		}
		return actions;
	}
	
	/**
	 * Ways to get closer: pearl catches toward the target or straight up
	 * (from the ground only), a flat lunge toward it, and a launch for
	 * height that later moves can use.
	 */
	private static void addApproach(GameState s, List<Action> actions,
		boolean pearls)
	{
		Kit kit = s.self().kit();
		float yaw = towardYaw(s);
		if(pearls && horizontalDistance(s) > DIAGONAL_RANGE)
			for(float pitch : CATCH_PITCHES)
				addIfNew(actions,
					new PearlCatch(new Rotation(yaw, pitch), CATCH_DELAY),
					canCatch(kit));
		if(pearls)
			addVerticalCatch(s, actions);
		if(kit.lungeSpear() >= 0 && kit.hand() >= 0)
			actions.add(new SpearLunge(yaw, 0));
		if(s.self().onGround() && kit.windCharge() >= 0)
		{
			actions.add(WindChargeJump.forward(yaw));
			actions.add(WindChargeJump.atFeet(false));
		}
	}
	
	private static void addVerticalCatch(GameState s, List<Action> actions)
	{
		PearlCatch vertical = new PearlCatch(
			new Rotation(towardYaw(s), VERTICAL_PITCH), CATCH_DELAY);
		addIfNew(actions,
			Eat.worthEating(s.self()) ? vertical.eating() : vertical,
			canCatch(s.self().kit()));
	}
	
	/**
	 * Low on health on the ground close to the target, with what an
	 * {@link Escape} needs.
	 */
	private static boolean inDanger(GameState s)
	{
		SelfState self = s.self();
		return self.onGround() && self.expectedHealth() < EMERGENCY_HEALTH
			&& self.pos().distanceTo(s.target().pos()) < DamageRace.THREAT_RANGE
			&& self.kit().windCharge() >= 0 && self.kit().goldenApple() >= 0;
	}
	
	private static boolean canCatch(Kit kit)
	{
		return kit.enderPearl() >= 0 && kit.windCharge() >= 0;
	}
	
	private static boolean below(GameState s)
	{
		return s.target().pos().y - s.self().pos().y > ABOVE;
	}
	
	/** Feet above the target's eyes. */
	private static double height(GameState s)
	{
		return s.self().pos().y - s.target().eyePos().y;
	}
	
	private static float towardYaw(GameState s)
	{
		return Engagement.aim(s.self().eyePos(), s.target().eyePos()).yaw();
	}
	
	private static void addStrikes(Level level, GameState s,
		List<Action> actions)
	{
		Decision decision = StrikeSelector.decide(Engagement.of(level, s));
		if(decision.action() != null)
			actions.add(decision.action());
		
		Kit kit = s.self().kit();
		for(PreItem pre : PreItem.values())
			addIfNew(actions, new MaceStrike(pre), kit.slotFor(pre) >= 0);
		addIfNew(actions, new StunSlam(),
			kit.axe() >= 0 && s.target().shieldRaised());
		addIfNew(actions, new EscapeLunge(),
			kit.lungeSpear() >= 0 && kit.hand() >= 0);
	}
	
	private static void addIfNew(List<Action> actions, Action action,
		boolean possible)
	{
		if(possible && !actions.contains(action))
			actions.add(action);
	}
	
	private static double horizontalDistance(GameState s)
	{
		return s.self().pos().subtract(s.target().pos()).horizontalDistance();
	}
}
