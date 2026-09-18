package com.pengcheng.phoenixgraph.metrics;

import org.junit.jupiter.api.Test;
import java.util.*;

class PercentileAccuracyTest {
    private static double exact(double[] sorted, double p) {
        int idx = Math.max(0, (int) Math.ceil(p * sorted.length) - 1);
        return sorted[idx] / 1e6;
    }

    private static double fromHistogram(LatencyStats stats, double p) {
        return stats.percentileMillis(p);
    }

    @Test
    void percentilesStayWithinAFewPercentOfTheTruth() {
        record Case(String name, double[] values) {}
        List<Case> cases = new ArrayList<>();
        double[] uniform = new double[1000];
        for (int i = 0; i < 1000; i++) uniform[i] = (i + 1) * 100_000L;          // 0.1 … 100 ms
        cases.add(new Case("uniform 0.1-100ms", uniform));
        double[] constant = new double[1000];
        Arrays.fill(constant, 3_000_000L);
        cases.add(new Case("constant 3ms", constant));
        double[] exponential = new double[2000];
        Random random = new Random(7);
        for (int i = 0; i < exponential.length; i++) exponential[i] = (long) (-Math.log(1 - random.nextDouble()) * 20_000_000L) + 1;
        cases.add(new Case("exponential mean 20ms", exponential));
        double[] wide = new double[2000];
        for (int i = 0; i < wide.length; i++) wide[i] = (long) Math.pow(10, 3 + random.nextDouble() * 5); // 1µs … 100s
        cases.add(new Case("log-uniform 1µs-100s", wide));

        StringBuilder report = new StringBuilder();
        for (Case c : cases) {
            double[] sorted = c.values().clone();
            Arrays.sort(sorted);
            LatencyStats stats = new LatencyStats();
            for (double v : c.values()) stats.record((long) v);
            for (double p : new double[]{0.5, 0.9, 0.95, 0.99}) {
                double exact = exact(sorted, p);
                double got = fromHistogram(stats, p);
                double error = Math.abs(got - exact) / exact * 100;
                report.append(String.format("%-24s p%-4.2f exact=%10.3f ms  hist=%10.3f ms  err=%5.2f%%%n",
                        c.name(), p, exact, got, error));
                org.junit.jupiter.api.Assertions.assertTrue(error < 5,
                        c.name() + " p" + p + " off by " + error + "%");
            }
        }
        System.out.println(report);
    }
}
