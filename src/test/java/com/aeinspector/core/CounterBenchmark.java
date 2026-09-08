package com.aeinspector.core;

import java.util.Arrays;

/** Standalone measurement of the accumulator only, not an end-to-end AE2 claim. */
public final class CounterBenchmark {
    public static void main(String[] args) {
        LongCounters counters = new LongCounters();
        for (int i = 0; i < 10000; i++) counters.add(i, 1);
        long[] samples = new long[2000];
        int key = 0;
        for (int tick = -2000; tick < samples.length; tick++) {
            long start = System.nanoTime();
            for (int call = 0; call < 5000; call++) {
                counters.add(key, 100000);
                if (++key == 10000) key = 0;
            }
            if (tick >= 0) samples[tick] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        System.out.println("Accumulator only: 5000 calls/tick, 10000 keys, 100000 items/call");
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.printf("p50=%.4f ms/tick; p95=%.4f ms/tick; p99=%.4f ms/tick%n",
                samples[1000] / 1e6, samples[1900] / 1e6, samples[1980] / 1e6);
        System.out.println("Primitive array payload bytes: " + counters.allocatedBytes());
    }
}
