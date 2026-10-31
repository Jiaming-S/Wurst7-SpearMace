/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Comparator;
import java.util.function.ToDoubleFunction;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.spearmace.input.KeyInput;
import net.wurstclient.hacks.spearmace.model.TargetModel;
import net.wurstclient.hacks.spearmace.plan.DefaultController;
import net.wurstclient.hacks.spearmace.plan.Options;
import net.wurstclient.hacks.spearmace.plan.Planner;
import net.wurstclient.hacks.spearmace.plan.Runner;
import net.wurstclient.hacks.spearmace.plan.Search;
import net.wurstclient.hacks.spearmace.plan.eval.DamageRace;
import net.wurstclient.hacks.spearmace.plan.eval.Weights;
import net.wurstclient.hacks.spearmace.plan.search.BeamSearch;
import net.wurstclient.hacks.spearmace.plan.search.Mcts;
import net.wurstclient.hacks.spearmace.plan.search.RuleSearch;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RotationUtils;

/**
 * Fights with a Lunge spear, a Wind Burst mace, wind charges and ender
 * pearls, mostly in the air: it closes in with pearl catches, lunges and
 * launches, then smashes the target with attribute-swapped mace hits and
 * rides each Wind Burst into the next one. It presses the same keys a
 * player would, and replans whenever the fight reaches a decision point.
 *
 * <p>
 * Settings take effect at the next replan, so they can be tuned mid-fight.
 * SpearMacePreview draws the current plan.
 */
@SearchTags({"spear mace", "mace bot", "MaceBot", "spear bot", "wind charge",
	"pearl catch"})
public final class SpearMaceHack extends Hack implements UpdateListener
{
	private final EnumSetting<SearchKind> search = new EnumSetting<>("Search",
		"How SpearMace picks what to do next.\n\n"
			+ "§lRules§r - The first move that the model expects to"
			+ " work, in a fixed order. Fastest.\n\n"
			+ "§lBeam search§r - Looks a few decision points ahead"
			+ " and keeps the best plans at each step.\n\n"
			+ "§lMCTS§r - Monte Carlo tree search over decision" + " points.",
		SearchKind.values(), SearchKind.BEAM);
	
	private final SliderSetting beamWidth = new SliderSetting("Beam width",
		"How many plans beam search keeps at each step.", 4, 1, 32, 1,
		ValueDisplay.INTEGER);
	
	private final SliderSetting lookahead = new SliderSetting("Lookahead",
		"How many decision points beam search looks ahead.", 3, 1, 12, 1,
		ValueDisplay.INTEGER);
	
	private final SliderSetting iterations = new SliderSetting(
		"MCTS iterations", "The most iterations MCTS runs per decision.", 60,
		10, 5000, 10, ValueDisplay.INTEGER);
	
	private final SliderSetting budget = new SliderSetting("Time budget",
		"How long a search may think at each decision point. Beam search"
			+ " and MCTS always finish their first step, so they can take"
			+ " longer. Above 50 ms, the game slows down unless its tick"
			+ " rate is lowered, which is meant for watching the search in"
			+ " SpearMacePreview.",
		50, 5, 5000, 5, ValueDisplay.INTEGER.withSuffix(" ms"));
	
	private final SliderSetting range =
		new SliderSetting("Range", "How far away SpearMace looks for targets.",
			48, 8, 96, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	
	private final EnumSetting<Priority> priority =
		new EnumSetting<>("Priority", "Which entity to go after first.",
			Priority.values(), Priority.DISTANCE);
	
	private final CheckboxSetting stickToTarget =
		new CheckboxSetting("Stick to target",
			"Keeps fighting the same target while it is in range, rather than"
				+ " switching whenever another one becomes a better pick.",
			true);
	
	private final SliderSetting fallDamageWeight =
		new SliderSetting("Fall damage weight",
			"How much SpearMace avoids taking fall damage, compared to dealing"
				+ " the same damage.",
			Weights.DEFAULT.fallDamage(), 0, 10, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting windChargeCost = new SliderSetting(
		"Wind charge cost", "How much damage a wind charge has to be worth.",
		Weights.DEFAULT.windCharge(), 0, 10, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting pearlCost = new SliderSetting("Pearl cost",
		"How much damage an ender pearl has to be worth.",
		Weights.DEFAULT.pearl(), 0, 20, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting heightValue = new SliderSetting("Height value",
		"How much each block above the target is worth, as the next smash"
			+ " turns it into damage.",
		Weights.DEFAULT.height(), 0, 5, 0.1, ValueDisplay.DECIMAL);
	
	private final SliderSetting distancePenalty =
		new SliderSetting("Distance penalty",
			"How much each block of distance beyond striking range costs."
				+ " Higher values close in more eagerly.",
			Weights.DEFAULT.distance(), 0, 5, 0.1, ValueDisplay.DECIMAL);
	
	private final SliderSetting healthValue = new SliderSetting("Health value",
		"How much each point of SpearMace's own health is worth, compared to"
			+ " dealing the same damage. Low health counts more.",
		Weights.DEFAULT.health(), 0, 5, 0.1, ValueDisplay.DECIMAL);
	
	private final SliderSetting appleCost = new SliderSetting(
		"Golden apple cost", "How much healing a golden apple has to be worth.",
		Weights.DEFAULT.apple(), 0, 20, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting threatPenalty =
		new SliderSetting("Threat penalty",
			"How much SpearMace avoids staying close to the target with low"
				+ " health.",
			Weights.DEFAULT.threat(), 0, 20, 0.5, ValueDisplay.DECIMAL);
	
	private final EntityFilterList entityFilters =
		EntityFilterList.genericMeleeCombat();
	
	private Runner runner;
	private Config config;
	private LivingEntity target;
	
	public SpearMaceHack()
	{
		super("SpearMace");
		setCategory(Category.COMBAT);
		addSetting(search);
		addSetting(beamWidth);
		addSetting(lookahead);
		addSetting(iterations);
		addSetting(budget);
		addSetting(range);
		addSetting(priority);
		addSetting(stickToTarget);
		addSetting(fallDamageWeight);
		addSetting(windChargeCost);
		addSetting(pearlCost);
		addSetting(heightValue);
		addSetting(distancePenalty);
		addSetting(healthValue);
		addSetting(appleCost);
		addSetting(threatPenalty);
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		// disable other killauras
		WURST.getHax().aimAssistHack.setEnabled(false);
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().crystalAuraHack.setEnabled(false);
		WURST.getHax().fightBotHack.setEnabled(false);
		WURST.getHax().killauraHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().triggerBotHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		
		// before the runner, so that the target is picked first
		EVENTS.add(UpdateListener.class, this);
		config = config();
		runner = new Runner(() -> target, planner(config));
		runner.start();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		if(runner != null)
			runner.stop();
		runner = null;
		target = null;
		KeyInput.releaseAll();
	}
	
	@Override
	public void onUpdate()
	{
		LivingEntity previous = target;
		target = findTarget();
		
		// the fight held the movement keys, so let go of them
		if(previous != null && target == null)
			KeyInput.releaseAll();
		
		Config now = config();
		if(!now.equals(config))
		{
			config = now;
			runner.setPlanner(planner(config));
		}
	}
	
	/** The current target if it still qualifies, otherwise the best one. */
	private LivingEntity findTarget()
	{
		double rangeSq = range.getValueSq();
		if(stickToTarget.isChecked() && target != null && target.isAlive()
			&& !target.isRemoved() && !target.isSpectator()
			&& MC.player.distanceToSqr(target) <= rangeSq
			&& entityFilters.testOne(target))
			return target;
		
		return entityFilters
			.applyTo(EntityUtils.getMeleeAttackableEntities()
				.filter(e -> e instanceof LivingEntity && !e.isSpectator())
				.filter(e -> MC.player.distanceToSqr(e) <= rangeSq))
			.min(priority.getSelected().comparator)
			.map(LivingEntity.class::cast).orElse(null);
	}
	
	/** The settings that shape the planner. */
	public record Config(SearchKind search, int width, int depth,
		int iterations, int budget, Weights weights)
	{}
	
	private Config config()
	{
		Weights d = Weights.DEFAULT;
		Weights weights = new Weights(fallDamageWeight.getValue(),
			windChargeCost.getValue(), pearlCost.getValue(), d.tick(),
			heightValue.getValue(), d.maxHeight(), d.ready(),
			distancePenalty.getValue(), healthValue.getValue(),
			appleCost.getValue(), threatPenalty.getValue());
		return new Config(search.getSelected(), beamWidth.getValueI(),
			lookahead.getValueI(), iterations.getValueI(), budget.getValueI(),
			weights);
	}
	
	/**
	 * A planner like SpearMace's, but with {@code c} instead of the
	 * settings, such as for a much slower expert search.
	 */
	public static Planner planner(Config c)
	{
		Search search = switch(c.search())
		{
			case RULES -> new RuleSearch();
			case BEAM -> new BeamSearch(c.width(), c.depth());
			case MCTS -> new Mcts(c.iterations(), 1, 1);
		};
		return new Planner(TargetModel.ballistic(), new Options(),
			new DefaultController(), search, new DamageRace(c.weights()),
			c.budget());
	}
	
	/**
	 * A new planner with the current settings, for SpearMacePreview's dry
	 * run.
	 */
	public Planner newPlanner()
	{
		return planner(config());
	}
	
	/** The target, for tests and other hacks. {@code null} if none. */
	public LivingEntity getTarget()
	{
		return target;
	}
	
	/** What the current fight has done so far. */
	public Runner getRunner()
	{
		return runner;
	}
	
	public enum SearchKind
	{
		RULES("Rules"),
		BEAM("Beam search"),
		MCTS("MCTS");
		
		private final String name;
		
		private SearchKind(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum Priority
	{
		DISTANCE("Distance", e -> MC.player.distanceToSqr(e)),
		ANGLE("Angle",
			e -> RotationUtils
				.getAngleToLookVec(e.getBoundingBox().getCenter())),
		HEALTH("Health", e -> e instanceof LivingEntity l ? l.getHealth()
			: Integer.MAX_VALUE);
		
		private final String name;
		private final Comparator<Entity> comparator;
		
		private Priority(String name, ToDoubleFunction<Entity> keyExtractor)
		{
			this.name = name;
			comparator = Comparator.comparingDouble(keyExtractor);
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
