/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.spearmace.combat.Engagement;
import net.wurstclient.hacks.spearmace.combat.Loadout;
import net.wurstclient.hacks.spearmace.maneuver.Hotbar;
import net.wurstclient.hacks.spearmace.maneuver.SpearLunge;
import net.wurstclient.hacks.spearmace.sim.Move;
import net.wurstclient.hacks.spearmace.sim.PearlSim;
import net.wurstclient.hacks.spearmace.sim.PlayerSim;
import net.wurstclient.hacks.spearmace.sim.Shots;
import net.wurstclient.hacks.spearmace.sim.StrikeModel;
import net.wurstclient.hacks.spearmace.sim.WindBurst;
import net.wurstclient.hacks.spearmace.sim.WindChargeSim;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.Kit;
import net.wurstclient.hacks.spearmace.state.Pending;
import net.wurstclient.hacks.spearmace.state.Pending.Ate;
import net.wurstclient.hacks.spearmace.state.Pending.Impulse;
import net.wurstclient.hacks.spearmace.state.Pending.PearlInFlight;
import net.wurstclient.hacks.spearmace.state.Pending.Projectile;
import net.wurstclient.hacks.spearmace.state.Pending.ServerAttack;
import net.wurstclient.hacks.spearmace.state.Pending.ServerLunge;
import net.wurstclient.hacks.spearmace.state.Pending.SelfDamage;
import net.wurstclient.hacks.spearmace.state.Pending.ServerUse;
import net.wurstclient.hacks.spearmace.state.Pending.TargetDamage;
import net.wurstclient.hacks.spearmace.state.Pending.Teleport;
import net.wurstclient.hacks.spearmace.state.Pending.WindChargeInFlight;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.hacks.spearmace.state.TargetState;
import net.wurstclient.hacks.spearmace.state.Vitals;
import net.wurstclient.hacks.spearmace.state.Vitals.Apple;
import net.wurstclient.util.Rotation;

/**
 * The forward model: what one update's {@link TickInput} does to a
 * {@link GameState}, one client tick at a time. It runs the same pieces
 * that the maneuvers' own predictions use, in the order the game runs
 * them.
 *
 * <p>
 * One {@link #step} goes from the state at update {@code n} to the state at
 * update {@code n + 1}:
 * <ol>
 * <li>the rest of client tick {@code n}: the player moves with the input's
 * view and keys, the attack charge and item cooldowns tick, and server
 * velocity changes that are due arrive</li>
 * <li>server tick {@code n}: it handles the clicks sent in client tick
 * {@code n}, then the player's pearls and wind charges move</li>
 * <li>{@code handleKeybinds()} of client tick {@code n + 1}: the hotbar
 * click, then the use and attack clicks, with the crosshair pick from
 * before the hotbar click</li>
 * </ol>
 *
 * <p>
 * Fall damage counts the fall only down to the server's impulse impact
 * position, which wind charge explosions and smashes set (see
 * {@link SelfState#impactY()}).
 *
 * <p>
 * Eating a golden apple takes {@link Apple#EAT_TICKS} with the use key
 * held, slows the movement keys and swallows other clicks. Its effects,
 * like fall damage, show up a few ticks after the server has them.
 * Regeneration heals as the client counts it down.
 *
 * <p>
 * Not modelled: blocks in the way of the crosshair pick, armor, effects
 * other than Regeneration, Absorption and Resistance, other things to eat,
 * damage from anything but falls and pearl teleports, and projectiles
 * hitting entities.
 */
public final class TickSim
{
	public static final int WIND_CHARGE_COOLDOWN = 10;
	public static final int PEARL_COOLDOWN = 20;
	
	/**
	 * The client starts a cooldown at the click, but only ends it when the
	 * server's cooldown ends, one tick later than its own.
	 */
	public static final int COOLDOWN_LAG = 1;
	
	public static final int MISS_TIME = 10;
	
	/**
	 * Server tick {@code n}'s velocity, position and health changes show
	 * up at tick n + this.
	 */
	public static final int MOTION_DELAY = 2;
	
	/**
	 * {@code LivingEntity.setIgnoreFallDamageFromCurrentImpulse()}: server
	 * ticks before standing on the ground clears the impact position.
	 */
	public static final int IMPULSE_GRACE = 40;
	
	/**
	 * A golden apple the server finished in tick {@code n} shows up at
	 * tick n + this (measured).
	 */
	public static final int EAT_DELAY = 3;
	
	/** {@code ThrownEnderpearl.onHit()} hurts the player this much. */
	public static final double PEARL_DAMAGE = 5;
	
	/** Fall distance that doesn't hurt ({@code SAFE_FALL_DISTANCE}). */
	public static final double SAFE_FALL = 3;
	
	/** Wind charges that fly longer than this are dropped. */
	private static final int MAX_CHARGE_AGE = 60;
	
	private final Level level;
	private final TargetModel targetModel;
	
	public TickSim(Level level, TargetModel targetModel)
	{
		this.level = level;
		this.targetModel = targetModel;
	}
	
	public Level level()
	{
		return level;
	}
	
	public Transition step(GameState state, TickInput input)
	{
		Step step = new Step(state);
		step.clientTick(input);
		step.serverTick();
		step.handleKeybinds(input);
		return step.result();
	}
	
	/** Steps {@code state} with each input in turn. */
	public List<Transition> rollout(GameState state, List<TickInput> inputs)
	{
		ArrayList<Transition> transitions = new ArrayList<>();
		for(TickInput input : inputs)
		{
			Transition t = step(state, input);
			transitions.add(t);
			state = t.next();
		}
		return transitions;
	}
	
	/** One step's working copy of the state. */
	private final class Step
	{
		private final SelfState before;
		private final int serverTick;
		private final int tick;
		private final EnumSet<Event> events = EnumSet.noneOf(Event.class);
		private final ArrayList<Pending> pending;
		private final ArrayList<Pending> sent = new ArrayList<>();
		
		private Vec3 pos;
		
		/** Where the server has the player, which is ahead on teleports. */
		private Vec3 serverPos;
		private Vec3 vel;
		private boolean onGround;
		private double fall;
		private double impactY;
		private int impulseGrace;
		private float yaw;
		private float pitch;
		private Move move;
		private boolean sprinting;
		private boolean jump;
		private int ticker;
		private boolean heldChanged;
		private int missTime;
		private Vitals vitals;
		private int useTicks;
		private Kit kit;
		private TargetState target;
		private double damageDealt;
		private double fallDamage;
		
		private Step(GameState state)
		{
			before = state.self();
			serverTick = before.tick();
			tick = before.tick() + 1;
			pending = new ArrayList<>(before.pending());
			pos = before.pos();
			vel = before.vel();
			onGround = before.onGround();
			fall = before.fallDistance();
			impactY = before.impactY();
			impulseGrace = before.impulseGrace();
			yaw = before.yaw();
			pitch = before.pitch();
			move = before.move();
			sprinting = before.sprinting();
			jump = before.jump();
			ticker = before.attackStrengthTicker();
			heldChanged = before.heldChanged();
			missTime = before.missTime();
			vitals = before.vitals();
			useTicks = before.useTicks();
			kit = before.kit();
			target = state.target();
		}
		
		private void clientTick(TickInput input)
		{
			if(input.rotation() != null)
			{
				yaw = input.rotation().yaw();
				pitch = input.rotation().pitch();
			}
			move = input.move();
			// LocalPlayer.canStartSprinting(): not while eating
			sprinting = input.sprint() && (sprinting || useTicks == 0);
			jump = input.jump();
			
			// LivingEntity.tick(): effects, then the item in use, then the
			// movement
			vitals = vitals.ticked();
			tickEating();
			
			PlayerSim sim = new PlayerSim(level, pos, vel, onGround);
			sim.yaw = yaw;
			sim.move = move;
			sim.sprinting = sprinting;
			sim.jump = jump;
			sim.usingItem = useTicks > 0 || events.contains(Event.ATE);
			sim.step();
			sprinting = sim.sprinting;
			
			// Entity.checkFallDamage(), but the server doesn't count the fall
			// between a smash and the arrival of its launch (measured)
			double dy = sim.pos.y - pos.y;
			if(dy < 0 && !launchPending())
				fall -= (float)dy;
			if(sim.onGround)
			{
				if(!onGround)
				{
					events.add(Event.LANDED);
					// Entity.checkFallDamage() only hurts after a fall
					if(fall > 0)
						land(sim.pos.y);
				}
				fall = 0;
				
				// ServerGamePacketListenerImpl.handlePlayerPositionChange()
				if(impulseGrace == 0)
					impactY = Double.NaN;
			}
			if(vel.y > 0 && sim.vel.y <= 0 && !sim.onGround)
				events.add(Event.APEX);
			
			pos = sim.pos;
			vel = sim.vel;
			onGround = sim.onGround;
			
			// Player.tick()
			ticker++;
			if(heldChanged)
				ticker = 0;
			heldChanged = false;
			kit = kit.withCooldownsTicked();
			
			if(target != null)
				target = targetModel.step(level, target);
			
			arrive();
		}
		
		/**
		 * {@code LivingEntity.updatingUsingItem()}: eating stops when the
		 * held item changes. The server finishes it, and the client keeps
		 * using the item until it hears so, which is before its next
		 * {@code handleKeybinds()}.
		 */
		private void tickEating()
		{
			if(useTicks <= 0)
				return;
			
			Apple apple = Kit.apple(kit.held());
			if(apple == null)
			{
				useTicks = 0;
				events.add(Event.STOPPED_EATING);
				return;
			}
			
			if(--useTicks > 0)
				return;
			
			events.add(Event.ATE);
			pending.add(new Ate(serverTick + EAT_DELAY, apple, kit.selected()));
		}
		
		/**
		 * {@code LivingEntity.causeFallDamage()} and
		 * {@code calculateFallDamage()}
		 */
		private void land(double y)
		{
			double counted =
				Double.isNaN(impactY) ? fall : Math.min(fall, impactY - y);
			fallDamage = Math.max(0, Math.floor(counted + 1e-6 - SAFE_FALL));
			if(counted <= 0 || fallDamage > 0)
				impactY = Double.NaN;
			if(fallDamage > 0)
				pending
					.add(new SelfDamage(serverTick + MOTION_DELAY, fallDamage));
		}
		
		/** {@code LivingEntity.setIgnoreFallDamageFromCurrentImpulse()} */
		private void impulseAt(double y)
		{
			impactY = y;
			impulseGrace = Math.max(impulseGrace, IMPULSE_GRACE);
		}
		
		/** Whether a smash launch is on its way to the player. */
		private boolean launchPending()
		{
			for(Pending p : pending)
				if(p instanceof Impulse i && !i.applied()
					&& !Double.isNaN(i.setY()))
					return true;
			return false;
		}
		
		/** What the server sent earlier shows up now. */
		private void arrive()
		{
			for(Pending p : List.copyOf(pending))
				switch(p)
				{
					case Impulse i when i.applied() -> pending.remove(i);
					case Teleport t when t.applied() -> pending.remove(t);
					case Impulse i when i.applyAt() == tick ->
					{
						vel = i.apply(vel);
						pending.set(pending.indexOf(i), i.asApplied());
						events.add(Event.IMPULSE);
					}
					case Teleport t when t.applyAt() == tick ->
					{
						pos = t.to();
						vel = Vec3.ZERO;
						onGround = false;
						vitals = vitals.hurt(PEARL_DAMAGE);
						pending.set(pending.indexOf(t), t.asApplied());
						events.add(Event.TELEPORTED);
					}
					case TargetDamage d when d.applyAt() == tick ->
					{
						if(target != null)
							target = target.withHealthLost(d.damage());
						pending.remove(d);
					}
					case SelfDamage d when d.applyAt() == tick ->
					{
						vitals = vitals.hurt(d.damage());
						pending.remove(d);
					}
					case Ate a when a.applyAt() == tick ->
					{
						vitals = vitals.ate(a.apple());
						if(Kit.apple(kit.get(a.slot())) == a.apple())
							kit = kit.withConsumed(a.slot());
						pending.remove(a);
					}
					default ->
						{
						}
				}
		}
		
		private void serverTick()
		{
			if(impulseGrace > 0)
				impulseGrace--;
			
			serverPos = pos;
			for(Pending p : pending)
				if(p instanceof Teleport t && !t.applied())
					serverPos = t.to();
				
			// packets first, in the order they were sent
			for(Pending p : List.copyOf(pending))
			{
				switch(p)
				{
					case ServerUse use -> spawn(use);
					case ServerAttack attack -> attack(attack);
					case ServerLunge lunge -> motion(lunge.impulse(),
						Double.NaN);
					default ->
					{
						continue;
					}
				}
				pending.remove(p);
			}
			
			// then entities, in the order they were added
			ArrayList<WindChargeInFlight> charges = new ArrayList<>();
			for(Pending p : pending)
				if(p instanceof WindChargeInFlight c)
					charges.add(c);
				
			for(Pending p : List.copyOf(pending))
				if(p instanceof PearlInFlight pearl)
					movePearl(pearl, charges);
				
			for(Pending p : List.copyOf(pending))
				if(p instanceof WindChargeInFlight charge)
					moveCharge(charge);
		}
		
		private void spawn(ServerUse use)
		{
			Vec3 dir = Shots.direction(use.yaw(), use.pitch());
			Vec3 shot = Shots.velocity(dir, Vec3.ZERO, use.knownMovement(),
				use.onGround());
			pending.add(switch(use.projectile())
			{
				case WIND_CHARGE -> new WindChargeInFlight(-1,
					WindChargeSim.spawnPos(use.feet()), shot, 0, false);
				case PEARL -> new PearlInFlight(-1,
					PearlSim.spawnPos(use.feet()), shot, 0);
			});
		}
		
		private void attack(ServerAttack attack)
		{
			if(target == null || target.invulnerable())
				return;
			
			if(target.blocks(attack.feet()))
			{
				if(attack.disableShieldTicks() > 0)
				{
					target =
						target.withShieldDisabled(attack.disableShieldTicks());
					events.add(Event.SHIELD_DISABLED);
				}else
					events.add(Event.BLOCKED);
				return;
			}
			
			// after the move that lands, the server counts the player as on
			// the ground but hasn't reset the fall distance yet, so the hit
			// still smashes but isn't a crit (measured)
			double damage = attack.damage();
			if(attack.crit() && onGround)
				damage /= StrikeModel.CRIT_MULTIPLIER;
			
			target = target.withHurt();
			pending.add(new TargetDamage(serverTick + MOTION_DELAY, damage));
			damageDealt += damage;
			events.add(Event.STRUCK);
			
			// MaceItem.postHurtEnemy() resets the server's fall distance, and
			// hurtEnemy() keeps the lower impact position
			if(attack.smash())
			{
				fall = 0;
				impulseAt(!Double.isNaN(impactY) && impactY <= serverPos.y
					? impactY : serverPos.y);
				
				// but Wind Burst's explosion isn't a wind charge's, so
				// onExplosionHit() ends the grace time, and standing on the
				// ground right after the smash clears the impact (measured)
				if(!Double.isNaN(attack.launchVelY()))
					impulseGrace = 0;
				motion(Vec3.ZERO, attack.launchVelY());
			}
		}
		
		private void motion(Vec3 add, double setY)
		{
			pending.add(new Impulse(serverTick + MOTION_DELAY, add, setY));
		}
		
		private void movePearl(PearlInFlight pearl,
			List<WindChargeInFlight> charges)
		{
			pending.remove(pearl);
			PearlSim sim = pearl.toSim();
			Vec3 from = sim.pos;
			Vec3 to = sim.accelerate();
			
			Vec3 blockHit = sim.clipBlocks(level, to);
			if(blockHit != null)
			{
				events.add(Event.PEARL_LANDED);
				teleport(blockHit);
				return;
			}
			
			for(WindChargeInFlight charge : charges)
				if(charge.toSim().box().inflate(WindChargeSim.margin(sim.age))
					.clip(from, to).isPresent())
				{
					teleport(from);
					return;
				}
			
			sim.finishMove(to);
			pending.add(PearlInFlight.of(pearl.entityId(), sim));
		}
		
		/**
		 * {@code ThrownEnderpearl.onHit()}: no velocity, no fall. The server
		 * resets the fall distance right away.
		 */
		private void teleport(Vec3 to)
		{
			fall = 0;
			impactY = Double.NaN;
			impulseGrace = 0;
			serverPos = to;
			pending.add(new Teleport(serverTick + MOTION_DELAY, to));
		}
		
		/**
		 * Moves a wind charge, which can hit the player once it has left
		 * them. The explosion pushes the player wherever the server has
		 * them.
		 */
		private void moveCharge(WindChargeInFlight charge)
		{
			pending.remove(charge);
			WindChargeSim sim = charge.toSim();
			AABB player = PlayerSim.box(serverPos);
			boolean left =
				charge.leftOwner() || WindChargeInFlight.isClearOf(sim, player);
			Vec3 center = sim.step(level, left ? player : null);
			if(center != null)
			{
				events.add(Event.EXPLODED);
				Vec3 knockback = WindBurst.knockback(level, center, serverPos);
				if(knockback.lengthSqr() > 0)
					motion(knockback, Double.NaN);
				
				// ServerPlayer.onExplosionHit()
				if(WindBurst.reaches(center, serverPos))
					impulseAt(serverPos.y);
				return;
			}
			
			if(sim.age <= MAX_CHARGE_AGE)
				pending
					.add(WindChargeInFlight.of(charge.entityId(), sim, left));
		}
		
		private void handleKeybinds(TickInput input)
		{
			if(missTime > 0)
				missTime--;
				
			// the crosshair pick and the attack charge still use the item
			// from before the hotbar click
			ItemStack picked = kit.held();
			if(input.slot() >= 0 && input.slot() != kit.selected())
			{
				kit = kit.withSelected(input.slot());
				if(!ItemStack.isSameItem(picked, kit.held()))
					heldChanged = true;
			}
			
			// Minecraft.handleKeybinds(): while using an item, letting go
			// of the use key stops it, and clicks do nothing
			if(useTicks > 0)
			{
				if(!input.useHeld())
				{
					useTicks = 0;
					events.add(Event.STOPPED_EATING);
				}
				pending.addAll(sent);
				return;
			}
			
			// a held use key starts again by itself, ignoring
			// rightClickDelay
			if(input.use() || input.useHeld() && Kit.isGoldenApple(kit.held()))
				use();
			if(input.attack())
				attack(picked);
			
			pending.addAll(sent);
		}
		
		private void use()
		{
			ItemStack held = kit.held();
			if(!kit.isReady(held))
				return;
			
			if(Kit.isGoldenApple(held))
			{
				useTicks = Apple.EAT_TICKS;
				events.add(Event.STARTED_EATING);
			}else if(held.is(Items.WIND_CHARGE))
				throwProjectile(Projectile.WIND_CHARGE, WIND_CHARGE_COOLDOWN,
					Event.THREW_WIND_CHARGE);
			else if(held.is(Items.ENDER_PEARL))
				throwProjectile(Projectile.PEARL, PEARL_COOLDOWN,
					Event.THREW_PEARL);
		}
		
		private void throwProjectile(Projectile projectile, int cooldown,
			Event event)
		{
			kit = kit.withUsed(kit.selected(), cooldown + COOLDOWN_LAG);
			sent.add(new ServerUse(projectile, pos, pos.subtract(before.pos()),
				onGround, yaw, pitch));
			events.add(event);
		}
		
		/** {@code Minecraft.startAttack()} */
		private void attack(ItemStack picked)
		{
			if(missTime > 0)
				return;
			
			// Player.cannotAttackWithItem(), with the old attack speed
			ItemStack held = kit.held();
			float minCharge =
				held.getOrDefault(DataComponents.MINIMUM_ATTACK_CHARGE, 0F);
			if(minCharge > 0 && ticker / kit.attackDelay(picked) < minCharge)
				return;
			
			if(held.has(DataComponents.PIERCING_WEAPON))
			{
				events.add(Event.JABBED);
				int lunge = Hotbar.lungeLevel(held);
				if(lunge > 0)
					sent.add(
						new ServerLunge(SpearLunge.impulse(yaw, pitch, lunge)));
				ticker = 0;
				return;
			}
			
			Vec3 look = new Rotation(yaw, pitch).toLookVec();
			if(target == null
				|| !Engagement.picks(pos.add(0, PlayerSim.EYE_HEIGHT, 0), look,
					vel, picked, target.box(), before.interactionRange(), 0))
			{
				events.add(Event.MISSED);
				missTime = MISS_TIME;
				ticker = 0;
				return;
			}
			
			sent.add(strike(picked, held));
			ticker = 0;
		}
		
		/**
		 * An attribute-swapped hit: damage and charge from {@code picked},
		 * smash and enchantments from {@code held}.
		 */
		private ServerAttack strike(ItemStack picked, ItemStack held)
		{
			// the server's charge is a tick behind (measured)
			double strength =
				StrikeModel.strength(ticker - 1, kit.attackDelay(picked));
			boolean crit = StrikeModel.isFull(strength) && !sprinting
				&& fall > 0 && !onGround;
			boolean mace = held.is(Items.MACE);
			int density = Loadout.level(held, Enchantments.DENSITY);
			double damage = StrikeModel.damage(kit.attackDamage(picked),
				strength, mace ? fall : 0, density, crit);
			
			boolean smash = mace && StrikeModel.canSmash(fall);
			double launch = Double.NaN;
			if(smash)
			{
				int windBurst = Loadout.level(held, Enchantments.WIND_BURST);
				double exposure =
					WindBurst.getSeenPercent(level, pos, PlayerSim.box(pos));
				launch = StrikeModel.windBurstVelY(windBurst, exposure,
					before.knockbackResistance());
			}
			
			int disable = (int)(Loadout.disableBlockingSeconds(held) * 20);
			return new ServerAttack(damage, crit, smash, launch, disable, pos);
		}
		
		private Transition result()
		{
			SelfState self = new SelfState(tick, pos, vel, onGround, fall,
				impactY, impulseGrace, yaw, pitch, move, sprinting, jump,
				before.fallFlying(), ticker, heldChanged, missTime,
				before.interactionRange(), before.knockbackResistance(), vitals,
				useTicks, kit, pending);
			return new Transition(new GameState(self, target), events,
				damageDealt, fallDamage);
		}
	}
}
