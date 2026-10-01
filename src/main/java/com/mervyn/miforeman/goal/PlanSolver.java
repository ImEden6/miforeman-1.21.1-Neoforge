package com.mervyn.miforeman.goal;

import com.mervyn.miforeman.goal.lp.StagedLp;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
        Map<String, Double> loopSupply = new TreeMap<>();
        Set<String> unsourced = new TreeSet<>();
        for (int i = 0; i < m.loops.size(); i++) {
            double amount = PlanMatrix.clamp(x[m.loopBase + i]);
            loopSupply.merge(m.loops.get(i).resource(), amount, Double::sum);
            if (amount > 0)
                unsourced.add(m.loops.get(i).resource());
        }
        Map<String, Double> surplus = new TreeMap<>();
        for (int i = 0; i < m.surplusIds.size(); i++)
            surplus.put(m.surplusIds.get(i), PlanMatrix.clamp(x[m.surplusBase + i]));
        return new Result(runs, imports, surplus, loopSupply, unsourced, staged.pivots());
    }
}
