/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.maneuver;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.sim.PearlSim;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.sim.Shots;
import net.wurstclient.hacks.spearmace.sim.WindChargeSim;
import net.wurstclient.util.Rotation;

/**
 * Decides where to throw the wind charge so that an ender pearl flies into
 * it.
 *
 * <h2>Timeline</h2>
 * Ticks are counted in client updates. A click queued at update {@code u}
 * is sent during client tick {@code u+1}, and the server handles it in
 * server tick {@code u+1+lag}, which runs after client tick {@code u+1}. The
 * server spawns the projectile at the player position from the movement
 * packet of client tick {@code u}, so after that tick's move.
 *
 * <p>
 * If the pearl spawned in server tick {@code spawnTick}, its move number
 * {@code m} happens in server tick {@code spawnTick+m-1}. A wind charge
 * spawned in the same server tick as move {@code m0} is already there when
 * the pearl does that move (packets are handled before entities tick), and
 * the pearl ticks before the charge because it was added earlier. When the
 * pearl's sweep hits the charge's box (inflated by the pearl's margin), the
 * owner is teleported to the pearl's position at the start of that move.
 *
 * <h2>Why the delay is the timing knob</h2>
 * Both projectiles start at the player's eyes and the charge flies in a
 * straight line, so for a given delay between the two throws there is
 * usually just one point on the pearl's path where both arrive at the same
 * time. A longer delay moves that point further along the path.
 */
public final class PearlCatchPlanner
{
	/** Chance per side that the server handles a click one tick off. */
	public static final double LAG_JITTER = 0.05;
	
	/**
	 * Chance that the server handles clicks from two consecutive client
	 * ticks in the same server tick. Gametests saw anything from 1 in 5 to 4
	 * in 5 throws, depending on how the client and server ticks line up, so
	 * a delay of 1 is treated as a coin flip.
	 */
	public static final double BATCH_CHANCE = 0.5;
	
	/** How many pearl moves off the plan a catch still counts as a hit. */
	public static final int CATCH_TOLERANCE = 1;
	
	/** How many aim candidates get a full Monte Carlo evaluation. */
	private static final int MAX_EVALUATED = 6;
	
	private final Level level;
	private final boolean checkBlocks;
	private final int samples;
	private final Random random = new Random();
	
	public PearlCatchPlanner(Level level, boolean checkBlocks, int samples)
	{
		this.level = level;
		this.checkBlocks = checkBlocks;
		this.samples = samples;
	}
	
	/**
	 * The pearl as far as the planner knows it.
	 *
	 * @param state
	 *            the pearl after {@code state.age} moves
	 * @param spawnTick
	 *            the update counter value minus the pearl's age, see the
	 *            class Javadoc
	 * @param observed
	 *            {@code true} once the real pearl was seen, so its random
	 *            spread is already known
	 */
	public record PearlInfo(PearlSim state, long spawnTick, Vec3 dir,
		Vec3 knownMovement, boolean onGround, boolean observed)
	{
		/** A pearl that is about to be thrown, before its spread is known. */
		public static PearlInfo nominal(Vec3 feet, Vec3 knownMovement,
			boolean onGround, Rotation aim, long spawnTick)
		{
			Vec3 dir = Shots.direction(aim.yaw(), aim.pitch());
			PearlSim state = new PearlSim(PearlSim.spawnPos(feet),
				Shots.velocity(dir, Vec3.ZERO, knownMovement, onGround), 0);
			return new PearlInfo(state, spawnTick, dir, knownMovement, onGround,
				false);
		}
		
		PearlSim sample(Random random)
		{
			if(observed || random == null)
				return state.copy();
			
			Vec3 vel = Shots.velocity(dir, Shots.sampleNoise(random),
				knownMovement, onGround);
			return new PearlSim(state.pos, vel, state.age);
		}
	}
	
	/**
	 * @param throwTick
	 *            the update at which to queue the wind charge click
	 * @param catchMove
	 *            the pearl move on which the catch happens (without spread)
	 * @param destination
	 *            where the player ends up (without spread)
	 */
	public record Plan(long throwTick, Rotation aim, int catchMove,
		Vec3 destination, double pSuccess)
	{
		@Override
		public String toString()
		{
			return String.format(
				"Plan[throw@%d aim=(%.2f, %.2f) move=%d dest=%s p=%.2f]",
				throwTick, aim.yaw(), aim.pitch(), catchMove,
				Prediction.fmt(destination), pSuccess);
		}
	}
	
	/** What happens to the pearl in one simulated throw. */
	public record Outcome(int move, Vec3 destination, boolean landed)
	{
		static final Outcome MISS = new Outcome(-1, null, false);
		
		public boolean caught()
		{
			return move > 0 && !landed;
		}
	}
	
	/**
	 * Finds the wind charge aim that catches the pearl most reliably when
	 * the charge is clicked at {@code throwTick}.
	 *
	 * @param now
	 *            the current update, at most {@code throwTick}
	 * @param track
	 *            the player's predicted states, {@code track.get(i)} being
	 *            the state at update {@code now+i}
	 * @param lag
	 *            extra server ticks between a click and its effect, as
	 *            measured from the pearl
	 * @param maxMove
	 *            catches after this pearl move are ignored
	 * @return the best plan, or {@code null} if no aim catches the pearl
	 */
	public Plan plan(PearlInfo pearl, long now, List<PlayerSim> track,
		long throwTick, int lag, int maxMove)
	{
		int i = (int)(throwTick - now);
		if(i < 0 || i + 1 >= track.size())
			return null;
		
		// a delay of 1 usually lands in the same server tick as the pearl
		int delay = (int)(throwTick - (pearl.spawnTick() - 1 - lag));
		boolean batched = delay == 1;
		int m0 =
			moveAtSpawn(throwTick, lag, pearl.spawnTick()) - (batched ? 1 : 0);
		PlayerSim before = track.get(i);
		PlayerSim after = track.get(i + 1);
		Vec3 spawn = WindChargeSim.spawnPos(after.pos);
		Vec3 knownMovement = after.pos.subtract(before.pos);
		boolean onGround = after.onGround;
		
		// aim at every segment of the pearl's path and keep the aims that
		// really catch it without spread
		List<Vec3> nominal = nominalPath(pearl.sample(null), maxMove);
		int firstAge = pearl.state().age;
		ArrayList<Plan> candidates = new ArrayList<>();
		for(int j = Math.max(m0, firstAge) + 1; j <= maxMove; j++)
		{
			Vec3 mid = nominal.get(j - 1 - firstAge)
				.add(nominal.get(j - firstAge)).scale(0.5);
			Rotation aim = aimAt(mid, spawn, knownMovement, onGround, j - m0);
			Outcome outcome = simulate(pearl.sample(null), m0, spawn,
				velocity(aim, Vec3.ZERO, knownMovement, onGround), maxMove);
			if(outcome.caught())
				candidates.add(new Plan(throwTick, aim, outcome.move(),
					outcome.destination(), 0));
		}
		
		if(candidates.isEmpty())
			return null;
		
		// evaluate a spread-out subset, as neighbors behave alike
		Plan best = null;
		int step = Math.max(1,
			(candidates.size() + MAX_EVALUATED - 1) / MAX_EVALUATED);
		for(int c = candidates.size() / 2 % step; c < candidates.size(); c +=
			step)
		{
			Plan candidate = candidates.get(c);
			double p = chance(pearl, m0, batched, spawn, knownMovement,
				onGround, candidate, maxMove);
			if(best == null || p > best.pSuccess())
				best = new Plan(throwTick, candidate.aim(),
					candidate.catchMove(), candidate.destination(), p);
		}
		
		return best;
	}
	
	/**
	 * The longest delay between the pearl click and the wind charge click
	 * that still catches with at least {@code minChance}, or -1 if none up
	 * to {@code maxDelay} does.
	 *
	 * @param pearlTick
	 *            the update of the pearl click
	 */
	public int maxFeasibleDelay(PearlInfo pearl, long pearlTick,
		List<PlayerSim> track, double minChance, int maxDelay, int maxMove)
	{
		for(int delay = maxDelay; delay >= 1; delay--)
		{
			Plan plan =
				plan(pearl, pearlTick, track, pearlTick + delay, 0, maxMove);
			if(plan != null && plan.pSuccess() >= minChance)
				return delay;
		}
		
		return -1;
	}
	
	/** The pearl move during which a charge clicked at {@code u} spawns. */
	public static int moveAtSpawn(long u, int lag, long spawnTick)
	{
		return (int)(u + 2 + lag - spawnTick);
	}
	
	/**
	 * The rotation that makes a wind charge reach {@code target} after
	 * {@code ticks} moves, as closely as its fixed speed allows.
	 */
	private static Rotation aimAt(Vec3 target, Vec3 spawn, Vec3 knownMovement,
		boolean onGround, int ticks)
	{
		Vec3 added = new Vec3(knownMovement.x, onGround ? 0 : knownMovement.y,
			knownMovement.z);
		Vec3 wanted = target.subtract(spawn).scale(1.0 / ticks).subtract(added);
		return Shots.rotationFor(wanted);
	}
	
	private static Vec3 velocity(Rotation aim, Vec3 noise, Vec3 knownMovement,
		boolean onGround)
	{
		return Shots.velocity(Shots.direction(aim.yaw(), aim.pitch()), noise,
			knownMovement, onGround);
	}
	
	private double chance(PearlInfo pearl, int m0, boolean batched, Vec3 spawn,
		Vec3 knownMovement, boolean onGround, Plan plan, int maxMove)
	{
		int hits = 0;
		for(int i = 0; i < samples; i++)
		{
			double r = random.nextDouble();
			int shift;
			if(batched)
				shift = r < BATCH_CHANCE ? 0 : 1;
			else
				shift = r < LAG_JITTER ? -1 : r < 2 * LAG_JITTER ? 1 : 0;
			Vec3 vel = velocity(plan.aim(), Shots.sampleNoise(random),
				knownMovement, onGround);
			Outcome outcome =
				simulate(pearl.sample(random), m0 + shift, spawn, vel, maxMove);
			if(outcome.caught() && Math
				.abs(outcome.move() - plan.catchMove()) <= CATCH_TOLERANCE)
				hits++;
		}
		
		return (double)hits / samples;
	}
	
	/**
	 * Runs the pearl and a wind charge together, server tick by server tick.
	 *
	 * @param pearl
	 *            the pearl after {@code pearl.age} moves (will be modified)
	 * @param m0
	 *            the pearl move during which the charge spawns
	 */
	public Outcome simulate(PearlSim pearl, int m0, Vec3 chargeSpawn,
		Vec3 chargeVel, int maxMove)
	{
		WindChargeSim charge = new WindChargeSim(chargeSpawn, chargeVel);
		while(pearl.age < maxMove)
		{
			int move = pearl.age + 1;
			Vec3 to = pearl.accelerate();
			
			if(checkBlocks)
			{
				Vec3 blockHit = pearl.clipBlocks(level, to);
				if(blockHit != null)
					return new Outcome(move, blockHit, true);
			}
			
			if(move >= m0
				&& charge.box().inflate(WindChargeSim.margin(pearl.age))
					.clip(pearl.pos, to).isPresent())
				return new Outcome(move, pearl.pos, false);
			
			pearl.finishMove(to);
			
			if(move >= m0)
			{
				if(!checkBlocks)
					charge.pos = charge.pos.add(charge.vel);
				else if(charge.step(level, null) != null)
					return Outcome.MISS;
			}
		}
		
		return Outcome.MISS;
	}
	
	/** Pearl positions by age, starting at {@code pearl.age}. */
	public static List<Vec3> nominalPath(PearlSim pearl, int maxAge)
	{
		ArrayList<Vec3> path = new ArrayList<>();
		path.add(pearl.pos);
		while(pearl.age < maxAge)
		{
			pearl.stepFree();
			path.add(pearl.pos);
		}
		return path;
	}
	
	/** Snapshots of {@code start} after 0, 1, ..., {@code ticks} steps. */
	public static List<PlayerSim> track(PlayerSim start, int ticks)
	{
		ArrayList<PlayerSim> track = new ArrayList<>();
		PlayerSim sim = start.copy();
		track.add(sim.copy());
		for(int i = 0; i < ticks; i++)
		{
			sim.step();
			sim.jump = false;
			track.add(sim.copy());
		}
		return track;
	}
}
