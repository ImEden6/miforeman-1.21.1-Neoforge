package com.mervyn.miforeman.goal;

import com.mervyn.miforeman.goal.lp.Simplex;
import com.mervyn.miforeman.goal.lp.StagedLp;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The most target output the linked machines can reach, and what limits it. Each recipe runs at
 * most its machine count at base speed (MI overclocking can beat that, so this is conservative);
 * raw inputs are assumed to keep up. A recipe the plan needs but has no machine for is a blocker.
 * A bottleneck is a recipe at full capacity where one more machine raises the output; when caps
 * bind together so that no single extra machine helps, all of them are reported as the joint limit.
 */
public final class CapacitySolver {
    private CapacitySolver() {
    }

    private static final int MAX_PROBES = 24;

    /** Output per minute; utilisation in [0, 1] per recipe with machines; recipe ids throughout. */
    public record Result(double maxRate, Map<String, Double> utilisation, Set<String> bottlenecks, Set<String> blockers) {}

    /** {@code needed} is the recipes the plan actually runs. Null when the LP can't be solved. */
    public static @Nullable Result solve(PlanSolver.Model model, Map<String, Integer> machines, Set<String> needed) {
        PlanMatrix m = PlanMatrix.of(model, true);
        Double maxRate = maxOutput(m, machines);
        if (maxRate == null)
            return null;

        Set<String> blockers = new TreeSet<>();
        for (String recipe : m.recipeIds)
            if (needed.contains(recipe) && machines.getOrDefault(recipe, 0) <= 0)
                blockers.add(recipe);

        double[] x = solveStaged(m, machines);
        Map<String, Double> utilisation = new TreeMap<>();
        List<String> binding = new ArrayList<>();
        if (x != null) {
            for (int v = 0; v < m.recipeIds.size(); v++) {
                String recipe = m.recipeIds.get(v);
                double cap = capacity(m, machines, v);
                if (cap <= 0)
                    continue;
                double used = PlanMatrix.clamp(x[v]) / cap;
                utilisation.put(recipe, Math.min(1.0, used));
                if (used >= 1 - 1e-6)
                    binding.add(recipe);
            }
        }

        Set<String> bottlenecks = new TreeSet<>();
        if (blockers.isEmpty()) {
            for (String recipe : binding.subList(0, Math.min(binding.size(), MAX_PROBES))) {
                Map<String, Integer> plusOne = new TreeMap<>(machines);
                plusOne.merge(recipe, 1, Integer::sum);
                Double raised = maxOutput(m, plusOne);
                if (raised != null && raised > maxRate * (1 + 1e-6) + 1e-9)
                    bottlenecks.add(recipe);
            }
            // Caps that only bind together: no single machine helps, but they are still the limit.
            if (bottlenecks.isEmpty())
                bottlenecks.addAll(binding);
        }
        return new Result(maxRate, utilisation, bottlenecks, blockers);
    }

    private static double capacity(PlanMatrix m, Map<String, Integer> machines, int v) {
        double duration = m.recipes.get(m.recipeIds.get(v)).durationTicks();
        return duration > 0 ? machines.getOrDefault(m.recipeIds.get(v), 0) * 1200.0 / duration : 0;
    }

    private static List<Simplex.Row> caps(PlanMatrix m, Map<String, Integer> machines) {
        List<Simplex.Row> rows = new ArrayList<>(m.upperBounds);
        for (int v = 0; v < m.recipeIds.size(); v++) {
            double[] row = new double[m.n];
            row[v] = 1;
            rows.add(new Simplex.Row(row, capacity(m, machines, v)));
        }
        return rows;
    }

    private static double[] outputObjective(PlanMatrix m) {
        double[] objective = new double[m.n];
        objective[m.outputVar] = 1;
        return objective;
    }

    private static @Nullable Double maxOutput(PlanMatrix m, Map<String, Integer> machines) {
        StagedLp.Result result = StagedLp.solve(m.equalities, caps(m, machines),
                List.of(StagedLp.Stage.maximize(outputObjective(m))));
        return result.ok() ? PlanMatrix.clamp(result.solution().x()[m.outputVar]) : null;
    }

    private static double @Nullable [] solveStaged(PlanMatrix m, Map<String, Integer> machines) {
        List<StagedLp.Stage> stages = new ArrayList<>();
        stages.add(StagedLp.Stage.maximize(outputObjective(m)));
        if (!m.loops.isEmpty())
            stages.add(StagedLp.Stage.minimize(m.loopCost()));
        stages.add(StagedLp.Stage.minimize(m.importCost()));
        stages.add(StagedLp.Stage.minimize(m.runCost()));
        StagedLp.Result result = StagedLp.solve(m.equalities, caps(m, machines), stages);
        return result.ok() ? result.solution().x() : null;
    }
}
