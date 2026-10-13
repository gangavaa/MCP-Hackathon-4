package com.nms.askdashboard.seed;

import java.util.List;
import java.util.Map;

/**
 * mulberry32, bit for bit the generator the TypeScript prototype used — so the same seed gives the same estate,
 * and the demo's numbers (README "The data") hold. Java's int arithmetic wraps exactly like JavaScript's
 * {@code Math.imul} and {@code >>> 0}.
 */
final class Rng {

  private int state;

  Rng(int seed) {
    this.state = seed;
  }

  double next() {
    state += 0x6d2b79f5;
    int t = state;
    t = (t ^ (t >>> 15)) * (t | 1);
    t ^= t + (t ^ (t >>> 7)) * (t | 61);
    return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
  }

  int nextInt(int min, int max) {
    return min + (int) Math.floor(next() * (max - min + 1));
  }

  boolean chance(double p) {
    return next() < p;
  }

  <T> T pick(List<T> items) {
    return items.get((int) Math.floor(next() * items.size()));
  }

  <T> T weighted(List<Map.Entry<T, Integer>> items) {
    int total = items.stream().mapToInt(Map.Entry::getValue).sum();
    double r = next() * total;
    for (Map.Entry<T, Integer> item : items) {
      if ((r -= item.getValue()) < 0) return item.getKey();
    }
    return items.get(items.size() - 1).getKey();
  }

  /** Knuth's method — fine for the small rates used here. */
  int poisson(double lambda) {
    double limit = Math.exp(-lambda);
    int k = 0;
    double p = 1;
    do {
      k++;
      p *= next();
    } while (p > limit);
    return k - 1;
  }
}
