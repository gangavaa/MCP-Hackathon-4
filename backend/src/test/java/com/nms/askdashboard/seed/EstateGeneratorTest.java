package com.nms.askdashboard.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.askdashboard.seed.EstateGenerator.Device;
import com.nms.askdashboard.seed.EstateGenerator.Estate;
import com.nms.askdashboard.seed.EstateGenerator.Farm;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The demo's answers, pinned — the same numbers the TypeScript prototype produced, so the port is exact. If a story
 * moves, these fail before the stage does; update README "The data" together with them.
 */
class EstateGeneratorTest {

  static final Instant ANCHOR = Instant.parse("2026-10-12T00:00:00Z");
  static final Estate ESTATE = EstateGenerator.generate(EstateGenerator.DEFAULT_SEED, ANCHOR);
  static final Map<String, Farm> FARMS = ESTATE.farms().stream().collect(Collectors.toMap(f -> f.id, Function.identity()));

  @Test
  void reproducesThePrototypeEstate() {
    assertThat(ESTATE.farms()).hasSize(110);
    assertThat(ESTATE.devices()).hasSize(5345);
    assertThat(ESTATE.issues()).hasSize(3083);
    assertThat(ESTATE.snapshots()).hasSize(6600);
  }

  @Test
  void isDeterministic() {
    Estate again = EstateGenerator.generate(EstateGenerator.DEFAULT_SEED, ANCHOR);
    assertThat(again.issues()).isEqualTo(ESTATE.issues());
  }

  @Test
  void keepsEveryTimestampBeforeTheAnchor() {
    assertThat(ESTATE.issues()).allMatch(i -> i.createdAt().isBefore(ANCHOR) && (i.resolvedAt() == null || !i.resolvedAt().isAfter(ANCHOR)));
    assertThat(ESTATE.devices()).allMatch(d -> d.lastSeenAt().isBefore(ANCHOR));
  }

  @Test
  void story1_hoeveDeLindeHasTheMostOfflineDevicesAllOnTheNewFirmware() {
    Map<String, Long> offline = ESTATE.devices().stream().filter(d -> d.status().equals("OFFLINE"))
        .collect(Collectors.groupingBy(Device::farmId, Collectors.counting()));
    Map.Entry<String, Long> top = offline.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
    assertThat(FARMS.get(top.getKey()).name).isEqualTo("Hoeve De Linde");
    assertThat(top.getValue()).isEqualTo(44);
    assertThat(offlineRate("5.16.0")).isGreaterThan(0.35);
    assertThat(offlineRate("5.15.0")).isLessThan(0.06);
  }

  @Test
  void story2_germanGatewayIssuesQuadrupleWeekOnWeek() {
    assertThat(germanGatewayIssues(0, 7)).isEqualTo(91);
    assertThat(germanGatewayIssues(7, 14)).isEqualTo(19);
  }

  @Test
  void story3_lowBatteryDevicesAreAnIrishCluster() {
    var low = ESTATE.devices().stream().filter(d -> d.status().equals("LOW_BATTERY")).toList();
    assertThat(low).hasSize(410);
    assertThat(low).allMatch(d -> FARMS.get(d.farmId()).country.equals("Ireland"));
  }

  private static double offlineRate(String firmware) {
    var on = ESTATE.devices().stream().filter(d -> d.firmwareVersion().equals(firmware)).toList();
    return on.stream().filter(d -> d.status().equals("OFFLINE")).count() / (double) on.size();
  }

  private static long germanGatewayIssues(int fromDaysAgo, int toDaysAgo) {
    return ESTATE.issues().stream().filter(i -> {
      double daysAgo = (ANCHOR.toEpochMilli() - i.createdAt().toEpochMilli()) / 86_400_000.0;
      return i.definitionId().equals("def-gateway") && FARMS.get(i.farmId()).country.equals("Germany")
          && daysAgo > fromDaysAgo && daysAgo <= toDaysAgo;
    }).count();
  }
}
