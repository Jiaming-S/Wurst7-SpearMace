/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.spearmace.model.InputInference;
import net.wurstclient.hacks.spearmace.model.Observer;
import net.wurstclient.hacks.spearmace.model.TargetModel;
import net.wurstclient.hacks.spearmace.model.TickInput;
import net.wurstclient.hacks.spearmace.model.TickSim;
import net.wurstclient.hacks.spearmace.model.Transition;
import net.wurstclient.hacks.spearmace.state.GameState;
import net.wurstclient.hacks.spearmace.state.SelfState;
import net.wurstclient.hacks.spearmace.viz.Preview;
import net.wurstclient.hacks.spearmace.viz.PreviewRenderer;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.EntityUtils;

/**
 * Draws what SpearMace's forward model expects to happen, so that its
 * predictions can be checked by eye. Without a plan from the bot, it
 * predicts the next few seconds if you keep holding the same keys and
 * click nothing. Your own clicks are worked out from what they do, so
 * throws and hits you make by hand are tracked too.
 */
@SearchTags({"spear mace preview", "SpearMace", "prediction"})
public final class SpearMacePreviewHack extends Hack
	implements UpdateListener, RenderListener
{
	private static final double TARGET_RANGE = 32;
	
	private final SliderSetting horizon =
		new SliderSetting("Horizon", "How many ticks ahead to predict.", 40, 5,
			100, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final CheckboxSetting showPlans = new CheckboxSetting("Show plans",
		"Draws the bot's current plan instead of the prediction, while there is one.",
		true);
	
	private final CheckboxSetting ghost = new CheckboxSetting("Ghost",
		"Also keeps one prediction in place and draws the path you really"
			+ " took next to it. A new one starts when you click or when it"
			+ " runs out.",
		false);
	
	private final ColorSetting selfColor = new ColorSetting("Prediction color",
		"Your predicted path.", Color.CYAN);
	
	private final ColorSetting targetColor = new ColorSetting("Target color",
		"The target's predicted path.", Color.RED);
	
	private final ColorSetting actualColor = new ColorSetting("Actual color",
		"The path you really took, in ghost mode.", Color.WHITE);
	
	/** The last captured and observed states, for tracking pending effects. */
	private GameState lastCaptured;
	private GameState lastObserved;
	
	private GameState start;
	private List<Transition> rollout = List.of();
	
	private GameState ghostStart;
	private List<Transition> ghostRollout = List.of();
	private final ArrayList<Vec3> trail = new ArrayList<>();
	
	public SpearMacePreviewHack()
	{
		super("SpearMacePreview");
		setCategory(Category.RENDER);
		addSetting(horizon);
		addSetting(showPlans);
		addSetting(ghost);
		addSetting(selfColor);
		addSetting(targetColor);
		addSetting(actualColor);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		lastCaptured = null;
		lastObserved = null;
		start = null;
		rollout = List.of();
		ghostStart = null;
		ghostRollout = List.of();
		trail.clear();
	}
	
	@Override
	public void onUpdate()
	{
		TickSim sim = new TickSim(MC.level, TargetModel.ballistic());
		GameState captured = GameState.capture(findTarget());
		
		// step the last observed state with the input that was really
		// used, so that throws and hits stay tracked until they land
		GameState predicted = null;
		boolean clicked = false;
		if(lastCaptured != null
			&& captured.self().tick() == lastCaptured.self().tick() + 1)
		{
			TickInput input =
				InputInference.infer(lastCaptured.self(), captured.self());
			predicted = sim.step(lastObserved, input).next();
			clicked = input.slot() >= 0 || input.attack() || input.use();
		}
		
		GameState observed = Observer.observe(captured, predicted);
		lastCaptured = captured;
		lastObserved = observed;
		
		SelfState self = observed.self();
		TickInput hold = new TickInput(null, self.forward(), self.sprinting(),
			self.jump(), -1, false, false);
		start = observed;
		rollout = sim.rollout(observed,
			Collections.nCopies(horizon.getValueI(), hold));
		
		if(!ghost.isChecked())
			return;
		
		if(ghostStart == null || clicked
			|| self.tick() - ghostStart.self().tick() >= horizon.getValueI())
		{
			ghostStart = observed;
			ghostRollout = rollout;
			trail.clear();
		}
		trail.add(self.pos());
	}
	
	/** The entity under the crosshair, or the closest one in range. */
	private LivingEntity findTarget()
	{
		if(MC.hitResult instanceof EntityHitResult hit
			&& hit.getEntity() instanceof LivingEntity e)
			return e;
		
		return EntityUtils.getMeleeAttackableEntities()
			.filter(LivingEntity.class::isInstance)
			.map(LivingEntity.class::cast)
			.filter(e -> MC.player.distanceTo(e) <= TARGET_RANGE)
			.min(Comparator.comparingDouble(e -> MC.player.distanceToSqr(e)))
			.orElse(null);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(start == null)
			return;
		
		Preview.Plan plan =
			showPlans.isChecked() ? Preview.latest(start.self().tick()) : null;
		if(plan != null)
			PreviewRenderer.draw(matrixStack, plan.start(), plan.rollout(),
				selfColor.getColorI(0xFF), targetColor.getColorI(0xFF));
		else
			PreviewRenderer.draw(matrixStack, start, rollout,
				selfColor.getColorI(0xFF), targetColor.getColorI(0xFF));
		
		if(ghost.isChecked() && ghostStart != null)
		{
			PreviewRenderer.draw(matrixStack, ghostStart, ghostRollout,
				selfColor.getColorI(0x60), targetColor.getColorI(0x60));
			PreviewRenderer.drawTrail(matrixStack, trail,
				actualColor.getColorI(0xFF));
		}
	}
}
