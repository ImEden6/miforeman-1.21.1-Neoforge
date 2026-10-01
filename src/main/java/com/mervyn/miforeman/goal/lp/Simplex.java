/*
 * Ported from gtnh-factory-flow, src/lib/solver/simplex.ts.
 *
 * MIT License
 *
 * Copyright (c) 2026 Samiracle64
 * Copyright (c) 2026 jackwrichards
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.mervyn.miforeman.goal.lp;

import java.util.Arrays;
import java.util.List;

/**
 * Dense two-phase simplex: maximise c.x subject to equality rows and {@code <=} rows, x >= 0.
 * Dantzig's entering rule, falling back to Bland's for good after a degenerate stall, with row
 * equilibration. Deterministic: the same program always takes the same pivots. Every answer is
 * checked against the original rows; a corrupt one is retried once under Bland's rule and
 * reported infeasible if that fails too.
 *
 * <p>Beyond the port: the ratio test runs on a slightly perturbed right-hand side while an exact
 * copy rides through the same pivots, and the answer is read from the exact copy. Plan models are
 * almost all zero-rhs balance rows, so without the perturbation nearly every vertex is degenerate
 * and large ones never finish.
 */
public final class Simplex {
    private Simplex() {
    }

    private static final double EPS = 1e-9;
    private static final double PERTURBATION = 1e-7;
    private static final int MAX_ITERATIONS = 100_000;

    /** {@code coefficients} is dense, one entry per variable. */
    public record Row(double[] coefficients, double rhs) {}

    public record Program(double[] maximize, List<Row> equalities, List<Row> upperBounds) {}

    public enum Status { OPTIMAL, INFEASIBLE, UNBOUNDED }

    /** {@code upperBoundDuals} holds one shadow price per {@code <=} row, in order; zero for rows
     *  that don't bind. {@code perturbedObjective} is the objective at the perturbed point, which a
     *  caller locking this optimum into a later solve must also admit. {@code pivots} counts every
     *  pivot, for deterministic cost checks. */
    public record Solution(Status status, double[] x, double objective, double perturbedObjective,
                           double[] upperBoundDuals, int pivots) {}

    public static Solution solve(Program lp) {
        Solution first = solveOnce(lp, false);
        if (first.status() != Status.OPTIMAL || holds(lp, first.x()))
            return first;
        Solution second = solveOnce(lp, true);
        int pivots = first.pivots() + second.pivots();
        if (second.status() == Status.OPTIMAL && holds(lp, second.x()))
            return new Solution(Status.OPTIMAL, second.x(), second.objective(), second.perturbedObjective(),
                    second.upperBoundDuals(), pivots);
        return new Solution(Status.INFEASIBLE, new double[0], Double.NaN, Double.NaN, new double[0], pivots);
    }

    private static boolean holds(Program lp, double[] x) {
        for (Row row : lp.equalities())
            if (!rowHolds(row, x, true))
                return false;
        for (Row row : lp.upperBounds())
            if (!rowHolds(row, x, false))
                return false;
        return true;
    }

    private static boolean rowHolds(Row row, double[] x, boolean equality) {
        double value = 0;
        double scale = 1 + Math.abs(row.rhs());
        double[] coefficients = row.coefficients();
        for (int c = 0; c < coefficients.length; c++) {
            double term = coefficients[c] * x[c];
            value += term;
            scale += Math.abs(term);
        }
        double violation = equality ? Math.abs(value - row.rhs()) : value - row.rhs();
        return violation <= 1e-6 * scale;
    }

    private static final class State {
        int pivots;
    }

    private static Solution solveOnce(Program lp, boolean blandFromStart) {
        int n = lp.maximize().length;
        int eqCount = lp.equalities().size();
        int m = eqCount + lp.upperBounds().size();

        // Normalised rows: every rhs >= 0, each row divided by its largest |coefficient|
        // (entries orders of magnitude apart amplify float error until the walk stops
        // terminating). rowSign * rowScale maps a tableau row back to its original row.
        double[][] rows = new double[m][];
        double[] rhs = new double[m];
        boolean[] equality = new boolean[m];
        double[] rowFactor = new double[m];
        for (int r = 0; r < m; r++) {
            Row source = r < eqCount ? lp.equalities().get(r) : lp.upperBounds().get(r - eqCount);
            equality[r] = r < eqCount;
            double scale = 0;
            for (double v : source.coefficients())
                scale = Math.max(scale, Math.abs(v));
            double factor = (scale <= 0 || (scale > 0.5 && scale < 2)) ? 1.0 : 1.0 / scale;
            rows[r] = new double[n];
            for (int c = 0; c < n; c++)
                rows[r][c] = source.coefficients()[c] * factor;
            rhs[r] = source.rhs() * factor;
            rowFactor[r] = factor;
        }

        // Column layout: [structural n][slack per <= row][artificial as needed]. A <= row with
        // negative rhs flips into a >= row, which takes a surplus column plus an artificial.
        int[] slackOf = new int[m];
        int[] surplusOf = new int[m];
        Arrays.fill(slackOf, -1);
        Arrays.fill(surplusOf, -1);
        int columns = n;
        for (int r = 0; r < m; r++) {
            if (!equality[r])
                slackOf[r] = columns++;
        }
        for (int r = 0; r < m; r++) {
            if (rhs[r] < 0) {
                for (int c = 0; c < n; c++)
                    rows[r][c] = -rows[r][c];
                rhs[r] = -rhs[r];
                rowFactor[r] = -rowFactor[r];
                if (!equality[r]) {
                    surplusOf[r] = slackOf[r];
                    slackOf[r] = -1;
                }
            }
        }
        int artificialStart = columns;
        int[] artificialOf = new int[m];
        Arrays.fill(artificialOf, -1);
        boolean anyArtificial = false;
        for (int r = 0; r < m; r++) {
            if (equality[r] || surplusOf[r] >= 0) {
                artificialOf[r] = columns++;
                anyArtificial = true;
            }
        }

        double[][] tableau = new double[m][columns + 2];
        int[] basis = new int[m];
        Arrays.fill(basis, -1);
        for (int r = 0; r < m; r++) {
            System.arraycopy(rows[r], 0, tableau[r], 0, n);
            if (slackOf[r] >= 0) {
                tableau[r][slackOf[r]] = 1;
                basis[r] = slackOf[r];
            }
            if (surplusOf[r] >= 0)
                tableau[r][surplusOf[r]] = -1;
            if (artificialOf[r] >= 0) {
                tableau[r][artificialOf[r]] = 1;
                basis[r] = artificialOf[r];
            }
            tableau[r][columns + 1] = rhs[r];
            tableau[r][columns] = rhs[r] + (absorbsNudge(rows[r], slackOf[r] >= 0)
                    ? PERTURBATION * (1 + (r % 1000) / 1000.0) : 0);
        }

        State state = new State();
        boolean[] banned = new boolean[columns];
        if (anyArtificial) {
            double[] phase1 = new double[columns];
            for (int a : artificialOf)
                if (a >= 0)
                    phase1[a] = -1;
            if (!runSimplex(tableau, basis, phase1, columns, banned, blandFromStart, state))
                return failed(Status.UNBOUNDED, state);
            double infeasibility = 0;
            for (int r = 0; r < m; r++)
                if (basis[r] >= artificialStart)
                    infeasibility += tableau[r][columns];
            if (infeasibility > 1e-7)
                return failed(Status.INFEASIBLE, state);
            // Drive degenerate artificials out of the basis through any non-artificial column,
            // slacks included, or phase 2 can regrow one and silently break its row. A row with
            // no such column is redundant and may keep its artificial.
            for (int r = 0; r < m; r++) {
                if (basis[r] < artificialStart)
                    continue;
                int bestCol = -1;
                double bestMag = EPS;
                for (int c = 0; c < artificialStart; c++) {
                    double mag = Math.abs(tableau[r][c]);
                    if (mag > bestMag) {
                        bestMag = mag;
                        bestCol = c;
                    }
                }
                if (bestCol >= 0)
                    pivot(tableau, basis, r, bestCol, columns, state);
            }
            for (int a : artificialOf)
                if (a >= 0)
                    banned[a] = true;
        }

        double[] objective = new double[columns];
        System.arraycopy(lp.maximize(), 0, objective, 0, n);
        if (!runSimplex(tableau, basis, objective, columns, banned, blandFromStart, state))
            return failed(Status.UNBOUNDED, state);

        // The exact copy gives the answer; if float drift left it slightly outside the feasible
        // region at this basis, the perturbed one (within tolerance of it) is used instead.
        boolean exactFeasible = true;
        for (int r = 0; r < m; r++)
            if (tableau[r][columns + 1] < -1e-7 * (1 + Math.abs(tableau[r][columns])))
                exactFeasible = false;
        int answerColumn = exactFeasible ? columns + 1 : columns;
        double[] x = new double[n];
        for (int r = 0; r < m; r++)
            if (basis[r] >= 0 && basis[r] < n)
                x[basis[r]] = Math.max(0, tableau[r][answerColumn]);
        double value = 0;
        double perturbedValue = 0;
        double[] perturbedX = new double[n];
        for (int r = 0; r < m; r++)
            if (basis[r] >= 0 && basis[r] < n)
                perturbedX[basis[r]] = Math.max(0, tableau[r][columns]);
        for (int c = 0; c < n; c++) {
            value += lp.maximize()[c] * x[c];
            perturbedValue += lp.maximize()[c] * perturbedX[c];
        }

        // Shadow prices: the reduced cost of a row's slack (or surplus) column is minus (or plus)
        // the tableau row's dual; rowFactor undoes the scaling and sign flip.
        double[] reduced = reducedCosts(tableau, basis, objective, columns);
        double[] duals = new double[lp.upperBounds().size()];
        for (int r = eqCount; r < m; r++) {
            double tableauDual = slackOf[r] >= 0 ? -reduced[slackOf[r]] : reduced[surplusOf[r]];
            duals[r - eqCount] = tableauDual * rowFactor[r];
        }
        return new Solution(Status.OPTIMAL, x, value, perturbedValue, duals, state.pivots);
    }

    /** A row can take a +delta nudge only through a slack or a positive coefficient; one only
     *  satisfiable at zero (all coefficients <= 0, rhs 0) would turn infeasible, so it stays exact. */
    private static boolean absorbsNudge(double[] row, boolean hasSlack) {
        if (hasSlack)
            return true;
        for (double v : row)
            if (v > EPS)
                return true;
        return false;
    }

    private static Solution failed(Status status, State state) {
        return new Solution(status, new double[0], Double.NaN, Double.NaN, new double[0], state.pivots);
    }

    private static double[] reducedCosts(double[][] tableau, int[] basis, double[] objective, int columns) {
        double[] reduced = Arrays.copyOf(objective, columns);
        for (int r = 0; r < tableau.length; r++) {
            double cost = basis[r] >= 0 ? objective[basis[r]] : 0;
            if (cost == 0)
                continue;
            double[] line = tableau[r];
            for (int c = 0; c < columns; c++)
                reduced[c] -= cost * line[c];
        }
        return reduced;
    }

    /** Maximises {@code objective} in place. Returns false on an unbounded ray or the iteration cap. */
    private static boolean runSimplex(double[][] tableau, int[] basis, double[] objective, int columns,
            boolean[] banned, boolean blandFromStart, State state) {
        int m = tableau.length;
        boolean blandMode = blandFromStart;
        int stalled = 0;
        double previousValue = Double.NEGATIVE_INFINITY;
        // Kept up to date per pivot instead of rebuilt (the rebuild was half the cost on large
        // plans), and rebuilt from scratch every so often and before declaring optimal, so float
        // drift in the running copy can never end the walk early.
        double[] reduced = reducedCosts(tableau, basis, objective, columns);
        int sinceRebuild = 0;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            if (sinceRebuild >= 50) {
                reduced = reducedCosts(tableau, basis, objective, columns);
                sinceRebuild = 0;
            }
            int entering = -1;
            if (blandMode) {
                for (int c = 0; c < columns; c++) {
                    if (!banned[c] && reduced[c] > 1e-9) {
                        entering = c;
                        break;
                    }
                }
            } else {
                double best = 1e-9;
                for (int c = 0; c < columns; c++) {
                    if (!banned[c] && reduced[c] > best) {
                        best = reduced[c];
                        entering = c;
                    }
                }
            }
            if (entering < 0) {
                if (sinceRebuild == 0)
                    return true;
                reduced = reducedCosts(tableau, basis, objective, columns);
                sinceRebuild = 0;
                continue;
            }

            // Two-pass ratio test: find the minimum ratio, then among near-ties take the largest
            // pivot element (Bland mode: the lowest basis index, which its anti-cycling proof needs).
            double best = Double.POSITIVE_INFINITY;
            for (int r = 0; r < m; r++) {
                double a = tableau[r][entering];
                if (a > EPS) {
                    double ratio = tableau[r][columns] / a;
                    if (ratio < best)
                        best = ratio;
                }
            }
            int leaving = -1;
            if (best < Double.POSITIVE_INFINITY) {
                double tieBand = EPS * (1 + Math.abs(best));
                double bestPivot = 0;
                for (int r = 0; r < m; r++) {
                    double a = tableau[r][entering];
                    if (a <= EPS)
                        continue;
                    double ratio = tableau[r][columns] / a;
                    if (ratio > best + tieBand)
                        continue;
                    if (blandMode) {
                        if (leaving < 0 || basis[r] < basis[leaving])
                            leaving = r;
                    } else if (a > bestPivot) {
                        bestPivot = a;
                        leaving = r;
                    }
                }
            }
            if (leaving < 0)
                return false;
            pivot(tableau, basis, leaving, entering, columns, state);
            double factor = reduced[entering];
            double[] line = tableau[leaving];
            for (int c = 0; c < columns; c++)
                reduced[c] -= factor * line[c];
            sinceRebuild++;
            if (!blandMode) {
                double value = 0;
                for (int r = 0; r < m; r++) {
                    double cost = basis[r] >= 0 ? objective[basis[r]] : 0;
                    if (cost != 0)
                        value += cost * tableau[r][columns];
                }
                if (value <= previousValue + 1e-12) {
                    if (++stalled > 60)
                        blandMode = true;
                } else {
                    stalled = 0;
                    previousValue = value;
                }
            }
        }
        return false;
    }

    private static void pivot(double[][] tableau, int[] basis, int row, int col, int columns, State state) {
        double[] line = tableau[row];
        double p = line[col];
        for (int c = 0; c <= columns + 1; c++)
            line[c] /= p;
        for (int r = 0; r < tableau.length; r++) {
            if (r == row)
                continue;
            double factor = tableau[r][col];
            if (Math.abs(factor) <= EPS)
                continue;
            double[] target = tableau[r];
            for (int c = 0; c <= columns + 1; c++)
                target[c] -= factor * line[c];
        }
        basis[row] = col;
        state.pivots++;
    }
}
