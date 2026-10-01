package com.mervyn.miforeman.goal.lp;

import java.util.ArrayList;
import java.util.List;

/** Solves objectives sequentially, locking each stage's optimum before the next runs. */
public final class StagedLp {
    private StagedLp() {
    }

    public record Stage(double[] objective, boolean maximize) {
        public static Stage minimize(double[] objective) {
            return new Stage(objective, false);
        }

        public static Stage maximize(double[] objective) {
            return new Stage(objective, true);
        }
    }

    public record Result(Simplex.Solution solution, int stagesSolved, int pivots, List<Simplex.Solution> solved) {
        public boolean ok() {
            return stagesSolved > 0;
        }
    }

    public static Result solve(List<Simplex.Row> equalities, List<Simplex.Row> upperBounds, List<Stage> stages) {
        List<Simplex.Row> bounds = new ArrayList<>(upperBounds);
        Simplex.Solution best = null;
        List<Simplex.Solution> solved = new ArrayList<>();
        int pivots = 0;
        for (int i = 0; i < stages.size(); i++) {
            Stage stage = stages.get(i);
            double[] c = stage.objective();
            double[] maximize = new double[c.length];
            for (int k = 0; k < c.length; k++)
                maximize[k] = stage.maximize() ? c[k] : -c[k];
            Simplex.Solution solution = Simplex.solve(new Simplex.Program(maximize, equalities, bounds));
            pivots += solution.pivots();
            if (solution.status() != Simplex.Status.OPTIMAL)
                return new Result(best != null ? best : solution, i, pivots, solved);
            best = solution;
            solved.add(solution);

            // Lock at looser optimum plus tolerance for float noise.
            double exact = stage.maximize() ? solution.objective() : -solution.objective();
            double perturbed = stage.maximize() ? solution.perturbedObjective() : -solution.perturbedObjective();
            double value = stage.maximize() ? Math.min(exact, perturbed) : Math.max(exact, perturbed);
            double tolerance = 1e-7 * (1 + Math.abs(value));
            double[] lock = new double[c.length];
            for (int k = 0; k < c.length; k++)
                lock[k] = stage.maximize() ? -c[k] : c[k];
            bounds.add(new Simplex.Row(lock, stage.maximize() ? -(value - tolerance) : value + tolerance));
        }
        return new Result(best, stages.size(), pivots, solved);
    }
}
