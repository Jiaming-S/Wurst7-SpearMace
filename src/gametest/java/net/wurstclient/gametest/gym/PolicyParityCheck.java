/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Checks that {@link PolicyFeatures} and {@link PolicyModel} compute what
 * Python computed, on the fixtures that {@code analysis/export_policy.py}
 * writes next to a model: logged decisions with Python's features and
 * probabilities. Needs nothing but Gson, so it runs without Minecraft:
 *
 * <pre>
 * java -cp build/classes/java/gametest:GSON_JAR \
 *   net.wurstclient.gametest.gym.PolicyParityCheck \
 *   spearmace-gym/policies/poc_full.json
 * </pre>
 *
 * Exits with status 1 if anything is off by more than the tolerances.
 */
public final class PolicyParityCheck
{
	/** Python keeps features as float32. */
	private static final double FEATURE_REL = 1e-5;
	private static final double FEATURE_ABS = 1e-4;
	private static final double PROB_ABS = 1e-4;
	
	private PolicyParityCheck()
	{}
	
	public static void main(String[] args) throws IOException
	{
		if(args.length != 1)
		{
			System.err.println("Usage: PolicyParityCheck MODEL.json");
			System.exit(2);
		}
		Path modelFile = Path.of(args[0]);
		String name = modelFile.getFileName().toString();
		Path fixtures = modelFile.resolveSibling(
			name.substring(0, name.length() - 5) + ".fixtures.jsonl");
		PolicyModel model = PolicyModel.load(modelFile);
		List<String> names = model.featureNames();
		
		int decisions = 0;
		int featureErrors = 0;
		int probErrors = 0;
		int pickErrors = 0;
		double worstFeature = 0;
		String worstName = null;
		double worstProb = 0;
		try(BufferedReader in =
			Files.newBufferedReader(fixtures, StandardCharsets.UTF_8))
		{
			for(String line; (line = in.readLine()) != null;)
			{
				if(line.isBlank())
					continue;
				JsonObject fixture =
					JsonParser.parseString(line).getAsJsonObject();
				JsonArray expectedF = fixture.getAsJsonArray("features");
				JsonArray expectedP = fixture.getAsJsonArray("probs");
				double[][] features = PolicyFeatures
					.of(fixture.getAsJsonObject("decision"), names);
				decisions++;
				
				if(features.length != expectedF.size())
				{
					System.err.printf(
						"decision %d: %d candidates, expected %d%n", decisions,
						features.length, expectedF.size());
					featureErrors++;
					continue;
				}
				
				double[] scores = new double[features.length];
				for(int i = 0; i < features.length; i++)
				{
					JsonArray row = expectedF.get(i).getAsJsonArray();
					for(int j = 0; j < names.size(); j++)
					{
						double want = row.get(j).getAsDouble();
						double diff = Math.abs(features[i][j] - want);
						if(diff > worstFeature)
						{
							worstFeature = diff;
							worstName = names.get(j);
						}
						if(diff > FEATURE_ABS + FEATURE_REL * Math.abs(want))
						{
							if(featureErrors < 10)
								System.err.printf(
									"decision %d candidate %d %s: java %.6f python %.6f%n",
									decisions, i, names.get(j), features[i][j],
									want);
							featureErrors++;
						}
					}
					scores[i] = model.score(features[i]);
				}
				
				double[] probs = PolicyModel.softmax(scores);
				int javaBest = 0;
				int pythonBest = 0;
				for(int i = 0; i < probs.length; i++)
				{
					double want = expectedP.get(i).getAsDouble();
					double diff = Math.abs(probs[i] - want);
					worstProb = Math.max(worstProb, diff);
					if(diff > PROB_ABS)
						probErrors++;
					if(probs[i] > probs[javaBest])
						javaBest = i;
					if(want > expectedP.get(pythonBest).getAsDouble())
						pythonBest = i;
				}
				if(javaBest != pythonBest)
					pickErrors++;
			}
		}
		
		System.out.printf(
			"%s: %d decisions, %d feature mismatches (worst %.2e in %s),"
				+ " %d probability mismatches (worst %.2e), %d different picks%n",
			model.name(), decisions, featureErrors, worstFeature, worstName,
			probErrors, worstProb, pickErrors);
		if(decisions == 0 || featureErrors > 0 || probErrors > 0
			|| pickErrors > 0)
			System.exit(1);
	}
}
