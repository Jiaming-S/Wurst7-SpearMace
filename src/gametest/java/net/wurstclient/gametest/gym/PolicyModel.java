/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.gym;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A candidate scorer exported by {@code analysis/export_policy.py}: an MLP
 * with ReLU between its linear layers that scores one candidate's
 * standardised features. The policy is the softmax of the scores over a
 * decision's candidates.
 *
 * <p>
 * Plain Java, so the gym needs no ML library. Immutable once loaded, so one
 * model can serve several searches.
 */
public final class PolicyModel
{
	private static final Map<Path, PolicyModel> CACHE =
		new ConcurrentHashMap<>();
	
	private final String name;
	private final String featureSet;
	private final List<String> featureNames;
	private final double[] mean;
	private final double[] std;
	private final double[][][] weights;
	private final double[][] biases;
	
	private PolicyModel(String name, JsonObject json)
	{
		this.name = name;
		featureSet = json.get("feature_set").getAsString();
		featureNames = new ArrayList<>();
		for(JsonElement e : json.getAsJsonArray("feature_names"))
			featureNames.add(e.getAsString());
		mean = vector(json.getAsJsonArray("mean"));
		std = vector(json.getAsJsonArray("std"));
		
		JsonArray layers = json.getAsJsonArray("layers");
		weights = new double[layers.size()][][];
		biases = new double[layers.size()][];
		int width = featureNames.size();
		for(int i = 0; i < layers.size(); i++)
		{
			JsonObject layer = layers.get(i).getAsJsonObject();
			JsonArray rows = layer.getAsJsonArray("weight");
			weights[i] = new double[rows.size()][];
			for(int r = 0; r < rows.size(); r++)
			{
				weights[i][r] = vector(rows.get(r).getAsJsonArray());
				if(weights[i][r].length != width)
					throw new IllegalArgumentException("Layer " + i + " has "
						+ weights[i][r].length + " inputs, expected " + width);
			}
			biases[i] = vector(layer.getAsJsonArray("bias"));
			width = rows.size();
		}
		if(width != 1)
			throw new IllegalArgumentException(
				"The last layer has " + width + " outputs, expected 1");
		if(mean.length != featureNames.size()
			|| std.length != featureNames.size())
			throw new IllegalArgumentException(
				"mean/std don't match the feature names");
	}
	
	/** Loads {@code file} once and keeps it for later calls. */
	public static PolicyModel load(Path file)
	{
		return CACHE.computeIfAbsent(file.toAbsolutePath().normalize(), f -> {
			try(Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8))
			{
				String fileName = f.getFileName().toString();
				String name = fileName.endsWith(".json")
					? fileName.substring(0, fileName.length() - 5) : fileName;
				return new PolicyModel(name,
					JsonParser.parseReader(r).getAsJsonObject());
			}catch(IOException e)
			{
				throw new IllegalStateException("Can't read policy " + f, e);
			}
		});
	}
	
	private static double[] vector(JsonArray array)
	{
		double[] v = new double[array.size()];
		for(int i = 0; i < v.length; i++)
			v[i] = array.get(i).getAsDouble();
		return v;
	}
	
	/** The score (logit) of one candidate's raw, unstandardised features. */
	public double score(double[] features)
	{
		double[] x = new double[features.length];
		for(int i = 0; i < x.length; i++)
			x[i] = (features[i] - mean[i]) / std[i];
		
		for(int layer = 0; layer < weights.length; layer++)
		{
			double[][] w = weights[layer];
			double[] next = new double[w.length];
			for(int r = 0; r < w.length; r++)
			{
				double sum = biases[layer][r];
				for(int c = 0; c < x.length; c++)
					sum += w[r][c] * x[c];
				boolean hidden = layer < weights.length - 1;
				next[r] = hidden ? Math.max(0, sum) : sum;
			}
			x = next;
		}
		return x[0];
	}
	
	/** The softmax of {@code scores}. */
	public static double[] softmax(double[] scores)
	{
		double max = Double.NEGATIVE_INFINITY;
		for(double s : scores)
			max = Math.max(max, s);
		double[] p = new double[scores.length];
		double sum = 0;
		for(int i = 0; i < p.length; i++)
			sum += p[i] = Math.exp(scores[i] - max);
		for(int i = 0; i < p.length; i++)
			p[i] /= sum;
		return p;
	}
	
	/** The file name without {@code .json}. */
	public String name()
	{
		return name;
	}
	
	/** {@code full} or {@code no_outcome}. */
	public String featureSet()
	{
		return featureSet;
	}
	
	/** The inputs {@link #score} takes, in order. */
	public List<String> featureNames()
	{
		return featureNames;
	}
}
