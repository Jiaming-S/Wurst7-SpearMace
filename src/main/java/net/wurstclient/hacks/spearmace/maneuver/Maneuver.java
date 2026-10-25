/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.maneuver;

/**
 * A movement primitive with a forward model ({@link #predict()}) and a
 * keypress-driven executor ({@link #tick(long)}). Instances are single-use.
 */
public interface Maneuver
{
	public String name();
	
	/**
	 * Predicts the outcome of starting this maneuver at the current update.
	 * Must not change any game state.
	 */
	public Prediction predict();
	
	/**
	 * Runs one update. The first call is the start of the maneuver.
	 *
	 * @param tick
	 *            the runner's update counter, which increases by one per
	 *            update
	 */
	public Status tick(long tick);
	
	/** Why the maneuver failed, or {@code null}. */
	public String failureReason();
	
	public enum Status
	{
		RUNNING,
		DONE,
		FAILED;
	}
}
