package com.mervyn.miforeman.goal;

import com.mervyn.miforeman.goal.lp.Simplex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The balance rows a {@link PlanSolver.Model} turns into, shared by {@link PlanSolver} and
 * {@link CapacitySolver}. Variables, in order: runs per recipe, raw imports, surplus per produced
 * resource, free supply per loop-closing edge, then optionally the target's output rate. Built in
 * sorted order throughout, so the same model always gives the same matrix.
 */
final class PlanMatrix {
    final TreeMap<String, PlanSolver.Recipe> recipes = new TreeMap<>();
    final List<String> recipeIds;
    final List<String> importIds;
    final List<String> surplusIds;
    final List<PlanSolver.BackEdge> loops = new ArrayList<>();
    final int importBase;
    final int surplusBase;
    final int loopBase;
    /** Index of the target-output variable, or -1 when the target's demand is fixed. */
    final int outputVar;
    final int n;
    final List<Simplex.Row> equalities = new ArrayList<>();
    final List<Simplex.Row> upperBounds = new ArrayList<>();

    private PlanMatrix(PlanSolver.Model model, boolean targetAsVariable) {
        for (PlanSolver.Recipe recipe : model.recipes())
            recipes.put(recipe.id(), recipe);

        TreeSet<String> resources = new TreeSet<>(model.raw());
        resources.add(model.target());
        TreeSet<String> produced = new TreeSet<>();
        for (PlanSolver.Recipe recipe : recipes.values()) {
            resources.addAll(recipe.inputs().keySet());
            resources.addAll(recipe.outputs().keySet());
            produced.addAll(recipe.outputs().keySet());
        }
        for (PlanSolver.BackEdge edge : model.backEdges())
            if (recipes.containsKey(edge.recipeId()) && recipes.get(edge.recipeId()).inputs().containsKey(edge.resource()))
                loops.add(edge);
        loops.sort(Comparator.comparing(PlanSolver.BackEdge::recipeId).thenComparing(PlanSolver.BackEdge::resource));

        recipeIds = new ArrayList<>(recipes.keySet());
        importIds = new ArrayList<>(new TreeSet<>(model.raw()));
        surplusIds = new ArrayList<>(produced);
        importBase = recipeIds.size();
        surplusBase = importBase + importIds.size();
        loopBase = surplusBase + surplusIds.size();
        outputVar = targetAsVariable ? loopBase + loops.size() : -1;
        n = loopBase + loops.size() + (targetAsVariable ? 1 : 0);

        Map<String, Integer> rowOf = new HashMap<>();
        List<String> rowIds = new ArrayList<>(resources);
        for (int r = 0; r < rowIds.size(); r++)
            rowOf.put(rowIds.get(r), r);
        double[][] rows = new double[rowIds.size()][n];
        for (int v = 0; v < recipeIds.size(); v++) {
            PlanSolver.Recipe recipe = recipes.get(recipeIds.get(v));
            for (var out : recipe.outputs().entrySet())
                rows[rowOf.get(out.getKey())][v] += out.getValue();
            for (var in : recipe.inputs().entrySet())
                rows[rowOf.get(in.getKey())][v] -= in.getValue();
        }
        for (int i = 0; i < importIds.size(); i++)
            rows[rowOf.get(importIds.get(i))][importBase + i] = 1;
        for (int i = 0; i < surplusIds.size(); i++)
            rows[rowOf.get(surplusIds.get(i))][surplusBase + i] = -1;
        for (int i = 0; i < loops.size(); i++) {
            PlanSolver.BackEdge edge = loops.get(i);
            rows[rowOf.get(edge.resource())][loopBase + i] = 1;
            // Free supply covers at most what the loop-closing recipe itself consumes.
            double[] cap = new double[n];
            cap[loopBase + i] = 1;
            cap[recipeIds.indexOf(edge.recipeId())] = -recipes.get(edge.recipeId()).inputs().get(edge.resource());
            upperBounds.add(new Simplex.Row(cap, 0));
        }
        if (targetAsVariable)
            rows[rowOf.get(model.target())][outputVar] = -1;

        for (int r = 0; r < rowIds.size(); r++) {
            double demand = !targetAsVariable && rowIds.get(r).equals(model.target()) ? model.targetRate() : 0;
            equalities.add(new Simplex.Row(rows[r], demand));
        }
    }

    static PlanMatrix of(PlanSolver.Model model, boolean targetAsVariable) {
        return new PlanMatrix(model, targetAsVariable);
    }

    double[] loopCost() {
        double[] cost = new double[n];
        for (int i = 0; i < loops.size(); i++)
            cost[loopBase + i] = weight(loops.get(i).resource());
        return cost;
    }

    double[] importCost() {
        double[] cost = new double[n];
        for (int i = 0; i < importIds.size(); i++)
            cost[importBase + i] = weight(importIds.get(i));
        return cost;
    }

    double[] machineCost() {
        double[] cost = new double[n];
        for (int v = 0; v < recipeIds.size(); v++)
            cost[v] = recipes.get(recipeIds.get(v)).durationTicks() / 1200.0;
        return cost;
    }

    double[] runCost() {
        double[] cost = new double[n];
        for (int v = 0; v < recipeIds.size(); v++)
            cost[v] = 1;
        return cost;
    }

    /** Every variable is non-negative, so anything under the dust threshold is float noise. */
    static double clamp(double value) {
        return value < 1e-9 ? 0 : value;
    }

    private static double weight(String resource) {
        return resource.startsWith("fluid:") ? PlanSolver.FLUID_IMPORT_WEIGHT : 1.0;
    }
}
