package com.mervyn.miforeman.goal;

import com.mervyn.miforeman.goal.lp.Simplex;
import com.mervyn.miforeman.goal.lp.StagedLp;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Exact run rates for a plan whose chosen recipes form loops or share outputs: one balance row
 * per resource (everything produced, byproducts included, minus everything consumed, plus imports,
 * minus surplus, equals demand), solved as a staged LP. Stages: draw as little free loop supply
 * as possible (only a loop with no outside input needs any), then import as little raw input
 * (recycle before importing), then use as few machines as possible (so no pointless loop runs),
 * then the least total running, for one deterministic answer. Each concern is its own stage
 * rather than a big penalty weight, which kept the simplex from converging on large graphs.
 *
 * <p>Free loop supply exists because MI's first-candidate recipes often form loops with no way
 * in (ingots packed from nuggets unpacked from ingots). It may only cover what the recipe closing
 * a loop consumes of the looped resource, which is exactly what the old propagation got for free
 * by dropping that edge, so such loops plan as before and get flagged instead of being cut at
 * whatever resource happens to be cheapest to buy.
 *
 * <p>Resources are keyed {@code item:<id>} or {@code fluid:<id>}, so an item never nets against
 * a same-named fluid. Rates are per minute, matching the rest of the plan.
 */
public final class PlanSolver {
    private PlanSolver() {
    }

    public static final double FLUID_IMPORT_WEIGHT = 0.001;

    /** Per-run amounts, already multiplied by probability. */
    public record Recipe(String id, double durationTicks, Map<String, Double> inputs, Map<String, Double> outputs) {}

    /** A loop-closing edge: {@code recipeId} consumes {@code resource} from further up its own loop. */
    public record BackEdge(String recipeId, String resource) {}

    public record Model(List<Recipe> recipes, Set<String> raw, String target, double targetRate,
                        List<BackEdge> backEdges) {}

    /** Runs per minute per recipe; imports, surplus, free loop supply and unsourced loop resources
     *  by resource key. */
    public record Result(Map<String, Double> runs, Map<String, Double> imports, Map<String, Double> surplus,
                         Map<String, Double> loopSupply, Set<String> unsourced, int pivots) {}

    /** Null when even the first stage can't be solved; callers fall back. */
    public static @Nullable Result solve(Model model) {
        TreeMap<String, Recipe> recipes = new TreeMap<>();
        for (Recipe recipe : model.recipes())
            recipes.put(recipe.id(), recipe);

        TreeSet<String> resources = new TreeSet<>(model.raw());
        resources.add(model.target());
        TreeSet<String> produced = new TreeSet<>();
        for (Recipe recipe : recipes.values()) {
            resources.addAll(recipe.inputs().keySet());
            resources.addAll(recipe.outputs().keySet());
            produced.addAll(recipe.outputs().keySet());
        }
        TreeSet<String> importable = new TreeSet<>(model.raw());
        List<BackEdge> loops = new ArrayList<>();
        for (BackEdge edge : model.backEdges())
            if (recipes.containsKey(edge.recipeId()) && recipes.get(edge.recipeId()).inputs().containsKey(edge.resource()))
                loops.add(edge);
        loops.sort(java.util.Comparator.comparing(BackEdge::recipeId).thenComparing(BackEdge::resource));

        List<String> recipeIds = new ArrayList<>(recipes.keySet());
        List<String> importIds = new ArrayList<>(importable);
        List<String> surplusIds = new ArrayList<>(produced);
        int importBase = recipeIds.size();
        int surplusBase = importBase + importIds.size();
        int loopBase = surplusBase + surplusIds.size();
        int n = loopBase + loops.size();

        Map<String, Integer> rowOf = new HashMap<>();
        List<String> rowIds = new ArrayList<>(resources);
        for (int r = 0; r < rowIds.size(); r++)
            rowOf.put(rowIds.get(r), r);
        double[][] rows = new double[rowIds.size()][n];
        for (int v = 0; v < recipeIds.size(); v++) {
            Recipe recipe = recipes.get(recipeIds.get(v));
            for (var out : recipe.outputs().entrySet())
                rows[rowOf.get(out.getKey())][v] += out.getValue();
            for (var in : recipe.inputs().entrySet())
                rows[rowOf.get(in.getKey())][v] -= in.getValue();
        }
        for (int i = 0; i < importIds.size(); i++)
            rows[rowOf.get(importIds.get(i))][importBase + i] = 1;
        for (int i = 0; i < surplusIds.size(); i++)
            rows[rowOf.get(surplusIds.get(i))][surplusBase + i] = -1;
        List<Simplex.Row> upperBounds = new ArrayList<>();
        for (int i = 0; i < loops.size(); i++) {
            BackEdge edge = loops.get(i);
            rows[rowOf.get(edge.resource())][loopBase + i] = 1;
            // Free supply covers at most what the loop-closing recipe itself consumes.
            double[] cap = new double[n];
            cap[loopBase + i] = 1;
            cap[recipeIds.indexOf(edge.recipeId())] = -recipes.get(edge.recipeId()).inputs().get(edge.resource());
            upperBounds.add(new Simplex.Row(cap, 0));
        }

        List<Simplex.Row> equalities = new ArrayList<>(rowIds.size());
        for (int r = 0; r < rowIds.size(); r++) {
            double demand = rowIds.get(r).equals(model.target()) ? model.targetRate() : 0;
            equalities.add(new Simplex.Row(rows[r], demand));
        }

        double[] loopCost = new double[n];
        for (int i = 0; i < loops.size(); i++)
            loopCost[loopBase + i] = loops.get(i).resource().startsWith("fluid:") ? FLUID_IMPORT_WEIGHT : 1.0;
        double[] importCost = new double[n];
        for (int i = 0; i < importIds.size(); i++)
            importCost[importBase + i] = importIds.get(i).startsWith("fluid:") ? FLUID_IMPORT_WEIGHT : 1.0;
        double[] machineCost = new double[n];
        double[] runCost = new double[n];
        for (int v = 0; v < recipeIds.size(); v++) {
            machineCost[v] = recipes.get(recipeIds.get(v)).durationTicks() / 1200.0;
            runCost[v] = 1;
        }

        List<StagedLp.Stage> stages = new ArrayList<>();
        if (!loops.isEmpty())
            stages.add(StagedLp.Stage.minimize(loopCost));
        stages.add(StagedLp.Stage.minimize(importCost));
        stages.add(StagedLp.Stage.minimize(machineCost));
        stages.add(StagedLp.Stage.minimize(runCost));
        StagedLp.Result staged = StagedLp.solve(equalities, upperBounds, stages);
        if (!staged.ok())
            return null;

        double[] x = staged.solution().x();
        Map<String, Double> runs = new TreeMap<>();
        for (int v = 0; v < recipeIds.size(); v++)
            runs.put(recipeIds.get(v), clamp(x[v]));
        Map<String, Double> imports = new TreeMap<>();
        for (int i = 0; i < importIds.size(); i++)
            imports.put(importIds.get(i), clamp(x[importBase + i]));
        Map<String, Double> loopSupply = new TreeMap<>();
        Set<String> unsourced = new TreeSet<>();
        for (int i = 0; i < loops.size(); i++) {
            double amount = clamp(x[loopBase + i]);
            loopSupply.merge(loops.get(i).resource(), amount, Double::sum);
            if (amount > 0)
                unsourced.add(loops.get(i).resource());
        }
        Map<String, Double> surplus = new TreeMap<>();
        for (int i = 0; i < surplusIds.size(); i++)
            surplus.put(surplusIds.get(i), clamp(x[surplusBase + i]));
        return new Result(runs, imports, surplus, loopSupply, unsourced, staged.pivots());
    }

    /** Every variable is non-negative, so anything under the dust threshold is float noise. */
    private static double clamp(double value) {
        return value < 1e-9 ? 0 : value;
    }
}
