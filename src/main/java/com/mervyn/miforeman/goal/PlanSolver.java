package com.mervyn.miforeman.goal;

import com.mervyn.miforeman.goal.lp.StagedLp;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Solves exact recipe run rates as a staged LP when recipes form loops or share outputs. */
public final class PlanSolver {
    private PlanSolver() {
    }

    public static final double FLUID_IMPORT_WEIGHT = 0.001;

    public record Recipe(String id, double durationTicks, Map<String, Double> inputs, Map<String, Double> outputs) {}

    public record BackEdge(String recipeId, String resource) {}

    public record Model(List<Recipe> recipes, Set<String> raw, String target, double targetRate,
                        List<BackEdge> backEdges) {}

    public record Result(Map<String, Double> runs, Map<String, Double> imports, Map<String, Double> surplus,
                         Map<String, Double> loopSupply, Set<String> unsourced, int pivots) {}

    public static @Nullable Result solve(Model model) {
        PlanMatrix m = PlanMatrix.of(model, false);
        List<StagedLp.Stage> stages = new ArrayList<>();
        if (!m.loops.isEmpty())
            stages.add(StagedLp.Stage.minimize(m.loopCost()));
        stages.add(StagedLp.Stage.minimize(m.importCost()));
        stages.add(StagedLp.Stage.minimize(m.machineCost()));
        stages.add(StagedLp.Stage.minimize(m.runCost()));
        StagedLp.Result staged = StagedLp.solve(m.equalities, m.upperBounds, stages);
        if (!staged.ok())
            return null;

        double[] x = staged.solution().x();
        Map<String, Double> runs = new TreeMap<>();
        for (int v = 0; v < m.recipeIds.size(); v++)
            runs.put(m.recipeIds.get(v), PlanMatrix.clamp(x[v]));
        Map<String, Double> imports = new TreeMap<>();
        for (int i = 0; i < m.importIds.size(); i++)
            imports.put(m.importIds.get(i), PlanMatrix.clamp(x[m.importBase + i]));
        boolean needsSupply = false;
        if (!m.loops.isEmpty()) {
            double[] loopStage = staged.solved().get(0).x();
            for (int i = 0; i < m.loops.size(); i++)
                needsSupply |= PlanMatrix.clamp(loopStage[m.loopBase + i]) > 0;
        }
        Map<String, Double> loopSupply = new TreeMap<>();
        Set<String> unsourced = new TreeSet<>();
        for (int i = 0; i < m.loops.size(); i++) {
            double amount = PlanMatrix.clamp(x[m.loopBase + i]);
            loopSupply.merge(m.loops.get(i).resource(), amount, Double::sum);
            if (needsSupply && amount > 0)
                unsourced.add(m.loops.get(i).resource());
        }
        Map<String, Double> surplus = new TreeMap<>();
        for (int i = 0; i < m.surplusIds.size(); i++)
            surplus.put(m.surplusIds.get(i), PlanMatrix.clamp(x[m.surplusBase + i]));
        return new Result(runs, imports, surplus, loopSupply, unsourced, staged.pivots());
    }

    public record LoopSupply(Map<BackEdge, Double> supply, int pivots) {}

    public static @Nullable LoopSupply loopSupply(Model model) {
        PlanMatrix m = PlanMatrix.of(model, false);
        if (m.loops.isEmpty())
            return new LoopSupply(Map.of(), 0);
        StagedLp.Result staged = StagedLp.solve(m.equalities, m.upperBounds, List.of(StagedLp.Stage.minimize(m.loopCost())));
        if (!staged.ok())
            return null;
        double[] x = staged.solution().x();
        Map<BackEdge, Double> supply = new java.util.LinkedHashMap<>();
        for (int i = 0; i < m.loops.size(); i++) {
            double amount = PlanMatrix.clamp(x[m.loopBase + i]);
            if (amount > 0)
                supply.merge(m.loops.get(i), amount, Double::sum);
        }
        return new LoopSupply(supply, staged.pivots());
    }

    public static double loopCost(Map<BackEdge, Double> supply) {
        double cost = 0;
        for (var e : supply.entrySet())
            cost += PlanMatrix.weight(e.getKey().resource()) * e.getValue();
        return cost;
    }

    public static boolean makesOnly(Recipe recipe, String resource) {
        return recipe.outputs().size() == 1 && recipe.outputs().containsKey(resource);
    }

    public static Set<String> obtainable(java.util.Collection<Recipe> recipes, Set<String> raw,
            java.util.Collection<BackEdge> free) {
        Set<String> obtainable = new java.util.HashSet<>(raw);
        Map<String, Set<String>> fed = new java.util.HashMap<>();
        for (BackEdge edge : free)
            fed.computeIfAbsent(edge.recipeId(), k -> new java.util.HashSet<>()).add(edge.resource());
        List<Recipe> pending = new ArrayList<>(recipes);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var it = pending.iterator(); it.hasNext(); ) {
                Recipe recipe = it.next();
                Set<String> own = fed.getOrDefault(recipe.id(), Set.of());
                boolean ready = true;
                for (var input : recipe.inputs().entrySet()) {
                    if (input.getValue() > 0 && !obtainable.contains(input.getKey()) && !own.contains(input.getKey())) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    obtainable.addAll(recipe.outputs().keySet());
                    it.remove();
                    changed = true;
                }
            }
        }
        return obtainable;
    }
}
