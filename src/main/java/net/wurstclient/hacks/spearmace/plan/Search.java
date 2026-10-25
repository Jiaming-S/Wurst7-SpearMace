/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

/** Finds a {@link Plan}, asking only the {@link SearchProblem}. */
@FunctionalInterface
public interface Search
{
	public Plan search(SearchProblem problem, Budget budget);
}
