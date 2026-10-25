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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.spearmace.input.KeyInput;
import net.wurstclient.hacks.spearmace.maneuver.PearlCatchPlanner.PearlInfo;
import net.wurstclient.hacks.spearmace.maneuver.PearlCatchPlanner.Plan;
import net.wurstclient.hacks.spearmace.sim.PearlSim;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
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
 * the pearl's own random spread. The wind charge's spread remains, so long
 * delays get unreliable; {@link #maxFeasibleDelay(Rotation, double)} finds
 * that limit.
 */
public final class PearlCatch implements Maneuver
{
	private static final Logger LOGGER = LoggerFactory.getLogger("SpearMace");
	private static final Minecraft MC = WurstClient.MC;
	
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
	
	private static final int AUTO = 0;
	private static final int MAX_AUTO_DELAY = 10;
	
	/** Teleports this far from the plan are counted as misses. */
	private static final double MISS_DISTANCE = 4;
	private static final int SAMPLES = 64;
	
	private final Rotation pearlAim;
	private int delay;
	
	private Phase phase = Phase.START;
	private String failureReason;
	private int pearlSlot;
	private int chargeSlot;
	private int prevSlot;
	private long pearlTick;
	private long chargeTick = -1;
	private long pearlGoneTick = -1;
	private final HashSet<Integer> oldPearls = new HashSet<>();
	private ThrownEnderpearl pearl;
	private PearlSim lastPearl;
	private PearlInfo info;
	private Plan plan;
	private int lag;
	private Vec3 lastPos;
	private Vec3 lastVel;
	private Vec3 teleportedTo;
	
	/**
	 * @param delay
	 *            updates between the pearl click and the wind charge click,
	 *            at least 1
	 */
	public PearlCatch(Rotation pearlAim, int delay)
	{
		this.pearlAim = pearlAim;
		this.delay = Math.max(1, delay);
	}
	
	/**
	 * Picks the most reliable delay (see {@link #bestDelay}) once the
	 * maneuver starts.
	 */
	public static PearlCatch mostReliable(Rotation pearlAim)
	{
		PearlCatch pearlCatch = new PearlCatch(pearlAim, 1);
		pearlCatch.delay = AUTO;
		return pearlCatch;
	}
	
	private void chooseDelay()
	{
		if(delay == AUTO)
			delay = bestDelay(pearlAim, MAX_AUTO_DELAY);
	}
	
	public int delay()
	{
		return delay;
	}
	
	@Override
	public String name()
	{
		return String.format("PearlCatch(yaw=%.1f, pitch=%.1f, delay=%d)",
			pearlAim.yaw(), pearlAim.pitch(), delay);
	}
	
	@Override
	public Prediction predict()
	{
		String reason = checkItems();
		if(reason != null)
			return Prediction.infeasible(reason);
		
		chooseDelay();
		if(delay < 1)
			return Prediction.infeasible("no delay catches the pearl");
		
		List<PlayerSim> track = newTrack();
		PearlInfo nominal = nominalPearl(track, 0);
		Plan plan = planFromHere(pearlAim, delay, track, nominal);
		if(plan == null)
			return Prediction
				.infeasible("no wind charge aim catches the pearl after "
					+ delay + " ticks");
		if(plan.pSuccess() < MIN_CHANCE)
			return Prediction.infeasible(String.format(
				"delay %d is out of range (p=%.2f)", delay, plan.pSuccess()));
			
		// teleport happens in server tick spawnTick+catchMove-1 and shows up
		// at the next update
		int arrival = (int)(nominal.spawnTick() + plan.catchMove());
		ArrayList<Vec3> path = new ArrayList<>();
		for(int i = 0; i < arrival && i < track.size(); i++)
			path.add(track.get(i).pos);
		path.add(plan.destination());
		return Prediction.of(path, Vec3.ZERO, plan.pSuccess());
	}
	
	/**
	 * Plans a catch as if the pearl was clicked now. If the wind charge
	 * waits until the real pearl is visible, the pearl's spread won't matter
	 * any more, so the chance only includes the charge's spread.
	 */
	private static Plan planFromHere(Rotation pearlAim, int delay,
		List<PlayerSim> track, PearlInfo nominal)
	{
		PearlInfo assumed = delay < SEEN_DELAY ? nominal
			: new PearlInfo(nominal.state(), nominal.spawnTick(), nominal.dir(),
				nominal.knownMovement(), nominal.onGround(), true);
		return newPlanner(true).plan(assumed, 0, track, delay, 0,
			MAX_CATCH_MOVE);
	}
	
	/** What a catch with a given delay would do, see {@link #survey}. */
	public record Option(int delay, Plan plan)
	{}
	
	/**
	 * Plans a catch for every delay up to {@code maxDelay}, as if the pearl
	 * was clicked now. Delays that can't catch the pearl at all are left
	 * out.
	 */
	public static List<Option> survey(Rotation pearlAim, int maxDelay)
	{
		List<PlayerSim> track = newTrack();
		PearlInfo nominal = nominalPearl(pearlAim, track, 0);
		ArrayList<Option> options = new ArrayList<>();
		for(int delay = 1; delay <= maxDelay; delay++)
		{
			Plan plan = planFromHere(pearlAim, delay, track, nominal);
			if(plan != null)
				options.add(new Option(delay, plan));
		}
		return options;
	}
	
	/** The most reliable delay up to {@code maxDelay}, or -1 if none. */
	public static int bestDelay(Rotation pearlAim, int maxDelay)
	{
		return survey(pearlAim, maxDelay).stream().max(
			(a, b) -> Double.compare(a.plan().pSuccess(), b.plan().pSuccess()))
			.map(Option::delay).orElse(-1);
	}
	
	/**
	 * The longest delay that still catches with at least {@code minChance},
	 * if the pearl was thrown now. -1 if none.
	 */
	public static int maxFeasibleDelay(Rotation pearlAim, double minChance)
	{
		List<PlayerSim> track = newTrack();
		PearlInfo nominal = nominalPearl(pearlAim, track, 0);
		return newPlanner(false).maxFeasibleDelay(nominal, 0, track, minChance,
			MAX_DELAY, MAX_CATCH_MOVE);
	}
	
	private static List<PlayerSim> newTrack()
	{
		return PearlCatchPlanner.track(PlayerSim.ofLocalPlayer(),
			MAX_CATCH_MOVE + 8);
	}
	
	@Override
	public Status tick(long tick)
	{
		Status status = switch(phase)
		{
			case START -> start(tick);
			case FLYING -> fly(tick);
		};
		
		lastPos = MC.player.position();
		lastVel = MC.player.getDeltaMovement();
		return status;
	}
	
	private Status start(long tick)
	{
		failureReason = checkItems();
		if(failureReason != null)
			return Status.FAILED;
		
		chooseDelay();
		if(delay < 1)
			return fail("no delay catches the pearl");
		
		pearlSlot = Hotbar.enderPearl();
		chargeSlot = Hotbar.windCharge();
		prevSlot = Hotbar.selected();
		for(Entity e : MC.level.entitiesForRendering())
			if(e instanceof ThrownEnderpearl)
				oldPearls.add(e.getId());
			
		List<PlayerSim> track = newTrack();
		info = nominalPearl(track, tick);
		plan = newPlanner(true).plan(info, tick, track, tick + delay, 0,
			MAX_CATCH_MOVE);
		if(plan == null)
			return fail("no throw catches the pearl");
		
		KeyInput.look(pearlAim);
		KeyInput.selectSlot(pearlSlot);
		KeyInput.clickUse();
		pearlTick = tick;
		phase = Phase.FLYING;
		log(tick, "pearl clicked, nominal plan " + plan);
		return Status.RUNNING;
	}
	
	private Status fly(long tick)
	{
		if(detectTeleport())
		{
			log(tick, "teleported to " + Prediction.fmt(teleportedTo));
			if(chargeTick < 0)
				return fail("teleported before the wind charge was thrown");
			
			// a pearl that misses the charge teleports wherever it lands
			if(pearlLanded()
				|| teleportedTo.distanceTo(plan.destination()) > MISS_DISTANCE)
				return fail("the pearl missed and landed at "
					+ Prediction.fmt(teleportedTo));
			
			return Status.DONE;
		}
		
		if(pearl != null && !pearl.isRemoved())
			lastPearl =
				new PearlSim(pearl.position(), pearl.getDeltaMovement(), 0);
		
		if(pearl == null)
			findPearl(tick);
		else if(pearl.isRemoved())
		{
			// the teleport can arrive a little after the removal
			if(pearlGoneTick < 0)
				pearlGoneTick = tick;
			else if(tick - pearlGoneTick > 3)
				return fail("pearl disappeared without a teleport");
		}
		
		if(pearl == null && tick - pearlTick > 6)
			return fail("pearl never showed up");
		
		if(chargeTick < 0 && plan != null && tick >= plan.throwTick())
		{
			KeyInput.look(plan.aim());
			KeyInput.selectSlot(chargeSlot);
			KeyInput.clickUse();
			chargeTick = tick;
			log(tick, "wind charge clicked with " + plan);
		}else if(chargeTick >= 0 && tick == chargeTick + 1
			&& prevSlot != chargeSlot)
			KeyInput.selectSlot(prevSlot);
		
		if(chargeTick < 0 && plan == null)
			return fail("lost the plan before throwing the wind charge");
		
		if(tick > info.spawnTick() + MAX_CATCH_MOVE)
			return fail("no catch");
		
		return Status.RUNNING;
	}
	
	private void findPearl(long tick)
	{
		for(Entity e : MC.level.entitiesForRendering())
		{
			if(!(e instanceof ThrownEnderpearl p)
				|| oldPearls.contains(p.getId()) || p.getOwner() != MC.player)
				continue;
			
			pearl = p;
			break;
		}
		
		if(pearl == null)
			return;
		
		// match the real pearl to the nominal path to learn its age
		List<Vec3> nominal =
			PearlCatchPlanner.nominalPath(info.state().copy(), 30);
		int age = 0;
		double bestDist = Double.MAX_VALUE;
		for(int i = 0; i < nominal.size(); i++)
		{
			double dist = nominal.get(i).distanceToSqr(pearl.position());
			if(dist < bestDist)
			{
				bestDist = dist;
				age = i;
			}
		}
		
		long spawnTick = tick - age;
		lag = (int)(spawnTick - (pearlTick + 1));
		info = new PearlInfo(
			new PearlSim(pearl.position(), pearl.getDeltaMovement(), age),
			spawnTick, info.dir(), info.knownMovement(), info.onGround(), true);
		log(tick,
			String.format("pearl seen: age=%d lag=%d pos=%s vel=%s", age, lag,
				Prediction.fmt(pearl.position()),
				Prediction.fmt(pearl.getDeltaMovement())));
		
		if(chargeTick >= 0)
			return;
		
		// keep the nominal aim if the real pearl can't be caught at all
		Plan replanned = newPlanner(true).plan(info, tick, newTrack(),
			pearlTick + delay, lag, MAX_CATCH_MOVE);
		if(replanned != null)
			plan = replanned;
		log(tick, "replanned: " + plan);
	}
	
	/**
	 * Whether the pearl, as last seen, was about to hit a block rather than
	 * the wind charge.
	 */
	private boolean pearlLanded()
	{
		if(lastPearl == null)
			return false;
		
		PearlSim sim = lastPearl.copy();
		for(int i = 0; i < 2; i++)
		{
			Vec3 to = sim.accelerate();
			if(sim.clipBlocks(MC.level, to) != null)
				return true;
			sim.finishMove(to);
		}
		
		return false;
	}
	
	private boolean detectTeleport()
	{
		if(lastPos == null)
			return false;
		
		Vec3 expected = lastPos.add(lastVel);
		Vec3 pos = MC.player.position();
		if(pos.distanceToSqr(expected) < 2 * 2)
			return false;
		
		teleportedTo = pos;
		return true;
	}
	
	public Vec3 teleportedTo()
	{
		return teleportedTo;
	}
	
	public Plan plan()
	{
		return plan;
	}
	
	private PearlInfo nominalPearl(List<PlayerSim> track, long now)
	{
		return nominalPearl(pearlAim, track, now);
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
	
	private static PearlCatchPlanner newPlanner(boolean checkBlocks)
	{
		return new PearlCatchPlanner(MC.level, checkBlocks, SAMPLES);
	}
	
	private String checkItems()
	{
		if(Hotbar.enderPearl() < 0)
			return "no ender pearl ready";
		if(Hotbar.windCharge() < 0)
			return "no wind charge ready";
		return null;
	}
	
	private Status fail(String reason)
	{
		failureReason = reason;
		return Status.FAILED;
	}
	
	private void log(long tick, String message)
	{
		LOGGER.info("[{}] {}: {}", tick, name(), message);
	}
	
	@Override
	public String failureReason()
	{
		return failureReason;
	}
	
	private enum Phase
	{
		START,
		FLYING;
	}
}
