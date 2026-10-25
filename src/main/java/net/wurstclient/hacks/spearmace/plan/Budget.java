/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.spearmace.plan;

/** How long a search may take. Searches that can stop early check it. */
public record Budget(long deadline)
{
	public static Budget millis(long millis)
	{
		return new Budget(System.nanoTime() + millis * 1_000_000);
	}
	
	public boolean left()
	{
		return System.nanoTime() < deadline;
	}
}
