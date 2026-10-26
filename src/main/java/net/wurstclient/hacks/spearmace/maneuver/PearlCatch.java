/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.maneuver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.action.Action;
import net.wurstclient.hacks.spearmace.action.Prediction;
import net.wurstclient.hacks.spearmace.action.Step;
import net.wurstclient.hacks.spearmace.maneuver.PearlCatchPlanner.PearlInfo;
import net.wurstclient.hacks.spearmace.maneuver.PearlCatchPlanner.Plan;
import net.wurstclient.hacks.spearmace.model.TickInput;
import net.wurstclient.hacks.spearmace.sim.PearlSim;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.state.Pending.PearlInFlight;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.util.Rotation;

/**
 * Throws an ender pearl, then a wind charge into its path, so that the pearl
 * hits the charge mid-air and teleports the player there.
 *
 * <p>
 * The pearl's rotation picks the direction (straight up for height,
 * diagonal for distance). {@code delay}, the ticks between the two clicks,
 * picks how far along the pearl's path the catch happens: a quick wind
 * charge catches it early, a late one further along. Before the pearl is
 * visible, the charge is aimed at its average flight path. Once the real
 * pearl shows up, the aim is redone with its actual velocity, which removes
 * the pearl's own random spread. The aim is redone once more right before
 * the wind charge is thrown, from where the player really is, so held keys
 * and knockback only matter for the tick between the click and the throw.
 * The wind charge's spread remains, so long delays get unreliable;
 * {@link #maxFeasibleDelay} finds that limit.
 *
 * @param delay
 *            updates between the pearl click and the wind charge click, at
 *            least 1, or {@link #AUTO}
 * @param minChance
 *            catches less likely than this are refused, {@link #MIN_CHANCE}
 *            unless data collection wants unlikely catches thrown too
 */
public record PearlCatch(Rotation pearlAim, int delay, double minChance)
	implements Action
{
	/** Catches less likely than this are refused. */
	public static final double MIN_CHANCE = 0.5;
	
	public static final int MAX_CATCH_MOVE = 60;
	public static final int MAX_DELAY = 30;
	
	/**
	 * Updates between the pearl click and the real pearl showing up on the
	 * client (measured in gametests). Wind charges clicked earlier have to
	 * be aimed at the pearl's average path.
	 */
	public static final int SEEN_DELAY = 3;
	
	/** Picks the most reliable delay when the catch starts. */
	public static final int AUTO = 0;
	private static final int MAX_AUTO_DELAY = 10;
	
	/** Teleports this far from the plan are counted as misses. */
	private static final double MISS_DISTANCE = 4;
	
	/** Updates without the pearl before it counts as gone. */
	private static final int MAX_MISSING = 4;
	
	private static final int SAMPLES = 64;
	
	/**
	 * What the catch did.
	 *
	 * @param plan
	 *            the plan the wind charge was thrown with
	 */
	public record Caught(Vec3 teleportedTo, int delay, Plan plan)
	{}
	
	public PearlCatch
	{
		if(delay != AUTO)
			delay = Math.max(1, delay);
	}
	
	public PearlCatch(Rotation pearlAim, int delay)
	{
		this(pearlAim, delay, MIN_CHANCE);
	}
	
	/**
	 * A catch whose pearl was thrown, as seen between updates, for data
	 * collection. {@code plan} and {@code chance} are the latest aim,
	 * {@code last} the state of the latest update.
	 */
	public interface InFlight
	{
		public int delay();
		
		public int clickTick();
		
		public Plan plan();
		
		public PearlInfo info();
		
		public double chance();
		
		/** When the wind charge was clicked, or -1. */
		public int chargeTick();
		
		public SelfState last();
	}
	
	/** Picks the most reliable delay (see {@link #bestDelay}). */
	public static PearlCatch mostReliable(Rotation pearlAim)
	{
		return new PearlCatch(pearlAim, AUTO);
	}
	
	@Override
	public String name()
	{
		return String.format("PearlCatch(yaw=%.1f, pitch=%.1f, delay=%s)",
			pearlAim.yaw(), pearlAim.pitch(), delay == AUTO ? "auto" : delay);
	}
	
	@Override
	public Step next(Level level, GameState s)
	{
		SelfState self = s.self();
		Kit kit = self.kit();
		String reason = checkItems(kit);
		if(reason != null)
			return Step.fail(reason);
		
		// its teleport would cut this catch short
		if(!self.pending(PearlInFlight.class).isEmpty())
			return Step.fail("a pearl is still in the air");
		
		int d = delay == AUTO ? bestDelay(level, self, pearlAim, MAX_AUTO_DELAY)
			: delay;
		if(d < 1)
			return Step.fail("no delay catches the pearl");
		
		int now = self.tick();
		List<PlayerSim> track = newTrack(level, self, pearlAim.yaw());
		PearlInfo nominal = nominalPearl(pearlAim, track, now);
		Plan expected = planFromHere(level, d, track, nominal, now);
		if(expected == null)
			return Step.fail(
				"no wind charge aim catches the pearl after " + d + " ticks");
		if(expected.pSuccess() < minChance)
			return Step.fail(String.format("delay %d is out of range (p=%.2f)",
				d, expected.pSuccess()));
		
		Plan plan = d >= SEEN_DELAY ? newPlanner(level, true).plan(nominal, now,
			track, now + d, 0, MAX_CATCH_MOVE) : expected;
		if(plan == null)
			return Step.fail("no throw catches the pearl");
		
		HashSet<Integer> oldPearls = new HashSet<>();
		for(PearlInFlight p : self.pending(PearlInFlight.class))
			oldPearls.add(p.entityId());
		
		Flying flying = new Flying(name(), d, now, kit.windCharge(),
			kit.selected(), plan, nominal, expected.pSuccess(), -1,
			Set.copyOf(oldPearls), null, now, self);
		TickInput click = TickInput.NONE.withRotation(pearlAim)
			.withSlot(kit.enderPearl()).withUse();
		return Step.run(click, flying).asCommitted();
	}
	
	/**
	 * Plans a catch as if the pearl was clicked at {@code now}. If the wind
	 * charge waits until the real pearl is visible, the pearl's spread won't
	 * matter any more, so the chance only includes the charge's spread.
	 */
	private static Plan planFromHere(Level level, int delay,
		List<PlayerSim> track, PearlInfo nominal, int now)
	{
		PearlInfo assumed = delay < SEEN_DELAY ? nominal
			: new PearlInfo(nominal.state(), nominal.spawnTick(), nominal.dir(),
				nominal.knownMovement(), nominal.onGround(), true);
		return newPlanner(level, true).plan(assumed, now, track, now + delay, 0,
			MAX_CATCH_MOVE);
	}
	
	/** What a catch with a given delay would do, see {@link #survey}. */
	public record Option(int delay, Plan plan)
	{}
	
	/**
	 * Plans a catch for every delay up to {@code maxDelay}, as if the pearl
	 * was clicked from {@code self}. Delays that can't catch the pearl at
	 * all are left out.
	 */
	public static List<Option> survey(Level level, SelfState self,
		Rotation pearlAim, int maxDelay)
	{
		List<PlayerSim> track = newTrack(level, self, pearlAim.yaw());
		PearlInfo nominal = nominalPearl(pearlAim, track, 0);
		ArrayList<Option> options = new ArrayList<>();
		for(int delay = 1; delay <= maxDelay; delay++)
		{
			Plan plan = planFromHere(level, delay, track, nominal, 0);
			if(plan != null)
				options.add(new Option(delay, plan));
		}
		return options;
	}
	
	/** The most reliable delay up to {@code maxDelay}, or -1 if none. */
	public static int bestDelay(Level level, SelfState self, Rotation pearlAim,
		int maxDelay)
	{
		return survey(level, self, pearlAim, maxDelay).stream().max(
			(a, b) -> Double.compare(a.plan().pSuccess(), b.plan().pSuccess()))
			.map(Option::delay).orElse(-1);
	}
	
	/**
	 * The longest delay that still catches with at least {@code minChance},
	 * if the pearl was thrown from {@code self}. -1 if none.
	 */
	public static int maxFeasibleDelay(Level level, SelfState self,
		Rotation pearlAim, double minChance)
	{
		List<PlayerSim> track = newTrack(level, self, pearlAim.yaw());
		PearlInfo nominal = nominalPearl(pearlAim, track, 0);
		return newPlanner(level, false).maxFeasibleDelay(nominal, 0, track,
			minChance, MAX_DELAY, MAX_CATCH_MOVE);
	}
	
	/**
	 * The player's predicted states from {@code self}, moving relative to
	 * {@code yaw} as the throw turns the view there.
	 */
	private static List<PlayerSim> newTrack(Level level, SelfState self,
		float yaw)
	{
		PlayerSim start = PlayerSim.of(level, self);
		start.yaw = yaw;
		return PearlCatchPlanner.track(start, MAX_CATCH_MOVE + 8);
	}
	
	/** The pearl clicked at update {@code now}, see the planner timeline. */
	private static PearlInfo nominalPearl(Rotation aim, List<PlayerSim> track,
		long now)
	{
		PlayerSim before = track.get(0);
		PlayerSim after = track.get(1);
		return PearlInfo.nominal(after.pos, after.pos.subtract(before.pos),
			after.onGround, aim, now + 1);
	}
	
	private static PearlCatchPlanner newPlanner(Level level,
		boolean checkBlocks)
	{
		return new PearlCatchPlanner(level, checkBlocks, SAMPLES);
	}
	
	private static String checkItems(Kit kit)
	{
		if(kit.enderPearl() < 0)
			return "no ender pearl ready";
		if(kit.windCharge() < 0)
			return "no wind charge ready";
		return null;
	}
	
	/**
	 * The pearl is in the air: throw the wind charge on time, re-aim it
	 * once the real pearl shows up and again right before the throw, and
	 * wait for the teleport.
	 *
	 * @param chargeTick
	 *            when the wind charge was clicked, or -1
	 * @param oldPearls
	 *            pearls that were already flying before this one
	 * @param pearl
	 *            this pearl when it was last in the state, or {@code null}
	 * @param pearlTick
	 *            the pearl click at first, then when {@code pearl} was last
	 *            in the state
	 */
	private record Flying(String name, int delay, int clickTick, int chargeSlot,
		int prevSlot, Plan plan, PearlInfo info, double chance, int chargeTick,
		Set<Integer> oldPearls, PearlInFlight pearl, int pearlTick,
		SelfState last) implements Action, InFlight
	{
		@Override
		public Step next(Level level, GameState s)
		{
			SelfState self = s.self();
			int tick = self.tick();
			
			Vec3 expected = last.pos().add(last.vel());
			if(self.pos().distanceToSqr(expected) >= 2 * 2)
				return teleported(level, self.pos());
			
			PearlInFlight seen = findPearl(self);
			PearlInfo info = this.info;
			if(seen != null && seen.entityId() >= 0 && !info.observed())
				// once the real pearl shows up, its spread is known
				info = new PearlInfo(seen.toSim(), tick - seen.age(),
					info.dir(), info.knownMovement(), info.onGround(), true);
				
			// held keys and knockback only show up in the observed state,
			// so the last aim before the throw starts from there
			Plan plan = this.plan;
			boolean throwNow = chargeTick < 0 && tick >= plan.throwTick();
			if(chargeTick < 0 && (throwNow || info != this.info))
			{
				Plan replanned = newPlanner(level, true).plan(info, tick,
					newTrack(level, self, self.yaw()),
					Math.max(tick, clickTick + delay), 0, MAX_CATCH_MOVE);
				if(replanned != null)
					plan = replanned;
				else if(throwNow)
					return Step.fail("no wind charge aim catches the pearl"
						+ " from here any more");
			}
			
			if(seen == null && tick - pearlTick > MAX_MISSING)
				return Step.fail(pearl == null ? "pearl never showed up"
					: "pearl disappeared without a teleport");
			if(tick > info.spawnTick() + MAX_CATCH_MOVE)
				return Step.fail("no catch");
			
			TickInput input = TickInput.NONE;
			int chargeTick = this.chargeTick;
			if(throwNow)
			{
				input = TickInput.NONE.withRotation(plan.aim())
					.withSlot(chargeSlot).withUse();
				chargeTick = tick;
			}else if(chargeTick >= 0 && tick == chargeTick + 1
				&& prevSlot != chargeSlot)
				input = TickInput.NONE.withSlot(prevSlot);
			
			Flying then =
				new Flying(name, delay, clickTick, chargeSlot, prevSlot, plan,
					info, plan == this.plan ? chance : plan.pSuccess(),
					chargeTick, oldPearls, seen != null ? seen : pearl,
					seen != null ? tick : pearlTick, self);
			// nothing else can be done until the pearl lands or is caught
			return Step.run(input, then).asCommitted();
		}
		
		@Override
		public double chance()
		{
			return chance;
		}
		
		private Step teleported(Level level, Vec3 to)
		{
			if(chargeTick < 0)
				return Step
					.fail("teleported before the wind charge was thrown");
			
			// a pearl that misses the charge teleports wherever it lands
			if(pearlLanded(level)
				|| to.distanceTo(plan.destination()) > MISS_DISTANCE)
				return Step.fail(
					"the pearl missed and landed at " + Prediction.fmt(to));
			
			return Step.done(new Caught(to, delay, plan));
		}
		
		private PearlInFlight findPearl(SelfState self)
		{
			for(PearlInFlight p : self.pending(PearlInFlight.class))
				if(p.entityId() < 0 || !oldPearls.contains(p.entityId()))
					return p;
			return null;
		}
		
		/**
		 * Whether the pearl, as last seen, was about to hit a block rather
		 * than the wind charge.
		 */
		private boolean pearlLanded(Level level)
		{
			if(pearl == null)
				return false;
			
			PearlSim sim = pearl.toSim();
			for(int i = 0; i < 2; i++)
			{
				Vec3 to = sim.accelerate();
				if(sim.clipBlocks(level, to) != null)
					return true;
				sim.finishMove(to);
			}
			return false;
		}
	}
}
