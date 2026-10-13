package com.nms.askdashboard.seed;

import static java.util.Map.entry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The estate the demo asks about. Vocabulary and shapes follow nms-core-ui's fake-backend — its tenant ids and
 * names, {@code TestFarmId<n>} farm ids, the connectivity statuses, the four severities, OPEN/CLOSED, the ten alert
 * definitions in {@code utils/issues.ts} — with realistic data in place of {@code TestFarmName<n>}.
 *
 * <p>Generated from a fixed seed with every timestamp relative to {@code anchor} (midnight UTC on the day it ran),
 * so the numbers are the same on every machine and every day. Three stories are planted for the demo to find:
 *
 * <ol>
 *   <li>FIRMWARE — six days before the anchor, firmware 5.16.0 rolled out to the collars and ear tags of 14 farms;
 *       those devices drop offline at ~10x the normal rate, "Hoeve De Linde" (NL) hardest.
 *   <li>GATEWAYS — German dairy farms raised four times as many "Gateway not answering" issues in the last 7 days.
 *   <li>BATTERIES — the ear tags on Irish feedlots are running flat.
 * </ol>
 *
 * <p>The order of every random draw matters: this is a line-for-line port of the TypeScript prototype, and
 * {@code EstateGeneratorTest} pins the numbers it must reproduce.
 */
public final class EstateGenerator {

  public static final int DEFAULT_SEED = 443929;
  static final int DAYS = 60;
  static final long DAY_MS = 86_400_000L;
  static final int ROLLOUT_DAYS_AGO = 6;
  static final String NEW_FIRMWARE = "5.16.0";

  // -------------------------------------------------------------------------------------------- reference data

  public record Tenant(String id, String name, String environment, String specie, String color, int farms) {}

  /* The app-wide tenants of fake-backend tenants/index.ts, ids kept. The fifth was "Some tenant..." there. */
  public static final List<Tenant> TENANTS = List.of(
      new Tenant("77c2c8fc-1266-46e5-937f-e1c84fe42fb8", "Dairy Prod", "Production", "Dairy", "#23885C", 40),
      new Tenant("2a703fc9-b51a-464f-829b-6b23aa5b3cc9", "Feedlot Prod", "Production", "Feedlot", "#004A75", 30),
      new Tenant("0576720d-0297-4d4b-bdaf-0e024db11611", "Poultry Prod", "Production", "Poultry", "#C08A2D", 25),
      new Tenant("e2362248-5c59-44e3-b5f3-d551a879835a", "Dairy Dev", "Development", "Dairy", "#7048CC", 8),
      new Tenant("6558cf86-ad58-4c58-9464-630cdff8a2ae", "Feedlot Staging", "Staging", "Feedlot", "#459CCE", 7));

  private record Country(String name, double lat, double lng, List<String> towns, List<String> owners, BiFunction<Rng, String, String> farmNames) {}

  private static final Map<String, Country> COUNTRIES = Map.of(
      "NL", new Country("Netherlands", 52.3, 5.6,
          List.of("Leeuwarden", "Drachten", "Zwolle", "Barneveld", "Gouda", "Assen", "Meppel"),
          List.of("van Wezel", "de Vries", "Jansen", "Bakker", "Visser", "Smit", "Mulder", "de Boer", "Bos", "Hendriks"),
          (rng, owner) -> rng.pick(List.of("Hoeve", "Boerderij", "Melkveebedrijf")) + " "
              + rng.pick(List.of("Zonnehoek", "Het Veld", "De Eik", "Groenendaal", "Weidezicht", "De Hoorn", "Het Hoogland", "De Wilgen", "Klaverweide", "De Meent"))),
      "DE", new Country("Germany", 52.9, 9.0,
          List.of("Kirchgellersen", "Lüneburg", "Oldenburg", "Cloppenburg", "Vechta", "Husum", "Rotenburg"),
          List.of("Müller", "Schmidt", "Fischer", "Weber", "Becker", "Hoffmann", "Schulz", "Koch", "Wagner", "Meyer"),
          (rng, owner) -> rng.pick(List.of("Gut", "Hof", "Milchhof")) + " "
              + rng.pick(List.of("Sonnenhof", "Lindenhof", "Birkenhof", "Eichenhof", "Wiesengrund", "Heidekamp", "Moorwiese", "Elbblick", "Buchenhain"))),
      "FR", new Country("France", 48.2, -1.6,
          List.of("Rennes", "Caen", "Laval", "Vitré", "Saint-Lô", "Fougères"),
          List.of("Martin", "Bernard", "Dubois", "Moreau", "Laurent", "Lefebvre", "Garnier"),
          (rng, owner) -> rng.pick(List.of("Ferme", "Domaine", "GAEC")) + " "
              + rng.pick(List.of("du Moulin", "des Prés", "de la Vallée", "des Chênes", "du Bocage", "de la Source", "des Tilleuls"))),
      "DK", new Country("Denmark", 56.2, 9.2,
          List.of("Herning", "Viborg", "Esbjerg", "Holstebro", "Skive", "Ribe"),
          List.of("Nielsen", "Jensen", "Hansen", "Pedersen", "Andersen", "Christensen"),
          (rng, owner) -> rng.pick(List.of("Søndergård", "Nørregård", "Østergård", "Vestergård", "Lindegård", "Bakkegård", "Engholm", "Kildegård", "Toftegård"))),
      "IE", new Country("Ireland", 52.6, -7.9,
          List.of("Cork", "Kilkenny", "Tipperary", "Mallow", "Clonmel", "Athlone", "Roscommon"),
          List.of("Murphy", "Kelly", "O'Brien", "Walsh", "Byrne", "Ryan", "O'Connor", "Doyle"),
          (rng, owner) -> rng.pick(List.of("Glenview", "Riverside", "Ashgrove", "Oakfield", "Hillcrest", "Meadowbrook", "Ballymore", "Rathmore", "Kilbrook")) + " "
              + rng.pick(List.of("Farm", "Feedlot", "Farms"))),
      "US", new Country("United States", 38.5, -101.0,
          List.of("Greeley, CO", "Dodge City, KS", "Amarillo, TX", "Garden City, KS", "Hereford, TX", "Yuma, CO"),
          List.of("Johnson", "Miller", "Anderson", "Thompson", "Harris", "Clark", "Lewis"),
          (rng, owner) -> rng.pick(List.of("Prairie Creek", "Cottonwood", "Red Mesa", "Sandhill", "Big Sky", "Cedar Bluff", "Arrowhead", "Lone Pine")) + " "
              + rng.pick(List.of("Feedyard", "Ranch", "Cattle Co."))));

  private static final Map<String, List<Map.Entry<String, Integer>>> COUNTRIES_BY_SPECIE = Map.of(
      "Dairy", List.of(entry("NL", 4), entry("DE", 4), entry("DK", 2), entry("IE", 2), entry("FR", 2)),
      "Feedlot", List.of(entry("US", 4), entry("IE", 3), entry("DE", 1)),
      "Poultry", List.of(entry("NL", 3), entry("FR", 3), entry("DE", 2), entry("DK", 1)));

  /* Gateway first in the TypeScript lists; devices other than the first draw from the rest. */
  private static final Map<String, List<Map.Entry<String, Integer>>> DEVICE_TYPES_WITHOUT_GATEWAY = Map.of(
      "Dairy", List.of(entry("Collar", 6), entry("Ear Tag", 3)),
      "Feedlot", List.of(entry("Ear Tag", 8), entry("Water Sensor", 1)),
      "Poultry", List.of(entry("Climate Sensor", 5), entry("Feeder Scale", 4)));

  private static final Map<String, String> HARDWARE = Map.of(
      "Collar", "SCR Heatime HR", "Ear Tag", "eSense Flex", "Gateway", "PS4", "Water Sensor", "AquaTrack 2",
      "Climate Sensor", "ClimaSense C3", "Feeder Scale", "WeighLine 500");

  /** The farm template every farm is created from — the seeded ones and those an admin adds. */
  public static final String FARM_TEMPLATE_ID = "ed3afa07-8c5d-4a1b-925c-9db0228f2caa";

  public record Definition(String id, String description, String severity, String category, int weight) {}

  /* The ten definitions of fake-backend utils/issues.ts, given short names that read on a chart axis. */
  public static final List<Definition> DEFINITIONS = List.of(
      new Definition("def-temperature", "Temperature above threshold", "CRITICAL", "Environment", 4),
      new Definition("def-battery", "Battery low", "LOW", "Technical", 8),
      new Definition("def-gateway", "Gateway not answering", "HIGH", "Technical", 3),
      new Definition("def-feed", "Feed level off schedule", "MEDIUM", "Operations", 6),
      new Definition("def-water", "Water consumption low", "MEDIUM", "Operations", 5),
      new Definition("def-humidity", "Humidity out of range", "HIGH", "Environment", 4),
      new Definition("def-door", "Door left open", "MEDIUM", "Operations", 3),
      new Definition("def-firmware", "Firmware rollout failed", "CRITICAL", "Technical", 1),
      new Definition("def-telemetry", "Telemetry gap", "LOW", "Technical", 4),
      new Definition("def-heartbeat", "Heartbeat missed", "MEDIUM", "Technical", 4));

  private static final Map<String, int[]> RESOLUTION_HOURS = Map.of(
      "CRITICAL", new int[] {2, 12}, "HIGH", new int[] {4, 24}, "MEDIUM", new int[] {8, 48}, "LOW", new int[] {12, 96});

  // -------------------------------------------------------------------------------------------- rows

  public static final class Farm {
    public String id, tenantId, templateId, name, farmName, specie, environment, connectivityStatus, version, protocol,
        contractUser, address, country, countryCode;
    public int animalsOnFarm;
    public double latitude, longitude;
    public Instant registeredAt, lastUpgradeAt;
  }

  public record Device(String id, String farmId, String deviceType, String hardwareType, String firmwareVersion, String status,
      int batteryPct, Instant lastSeenAt, Instant registeredAt) {}

  public record Issue(String id, String definitionId, String tenantId, String farmId, String resourceId, String severity,
      String alertState, Instant createdAt, Instant resolvedAt) {}

  public record Snapshot(String farmId, LocalDate day, int online, int offline, int total) {}

  public record Estate(Instant anchor, List<Farm> farms, List<Device> devices, List<Issue> issues, List<Snapshot> snapshots) {}

  // -------------------------------------------------------------------------------------------- generation

  /** Pure: the same seed and anchor always give the same estate. */
  public static Estate generate(int seed, Instant anchor) {
    Rng rng = new Rng(seed);
    long anchorMs = anchor.toEpochMilli();
    // Day 0 is the last full day before the anchor (midnight UTC), so day buckets line up with calendar dates.
    BiFunction<Integer, Double, Instant> at = (daysAgo, hours) ->
        Instant.ofEpochMilli((long) (anchorMs - (daysAgo + 1) * (double) DAY_MS + hours * 3_600_000));
    Instant rollout = at.apply(ROLLOUT_DAYS_AGO, 2.0);

    // Farms ---------------------------------------------------------------------------------------------------
    List<Farm> farms = new ArrayList<>();
    Set<String> usedNames = new HashSet<>();
    for (Tenant tenant : TENANTS) {
      for (int i = 0; i < tenant.farms(); i++) {
        int index = farms.size();
        boolean story = index == 0; // Hoeve De Linde: the first Dairy Prod farm
        String code = story ? "NL" : rng.weighted(COUNTRIES_BY_SPECIE.get(tenant.specie()));
        Country country = COUNTRIES.get(code);
        String owner = story ? "van Wezel" : rng.pick(country.owners());
        String name = story ? "Hoeve De Linde" : country.farmNames().apply(rng, owner);
        for (int n = 2; usedNames.contains(name); n++) name = name.replaceAll(" \\d+$", "") + " " + n;
        usedNames.add(name);

        Farm farm = new Farm();
        farm.id = "TestFarmId" + index;
        farm.countryCode = code;
        farm.tenantId = tenant.id();
        farm.templateId = FARM_TEMPLATE_ID;
        farm.name = name;
        farm.farmName = owner;
        farm.specie = tenant.specie();
        farm.environment = tenant.environment();
        farm.connectivityStatus = "ONLINE"; // derived from its devices below
        farm.version = rng.weighted(List.of(entry("7.5.6.3", 5), entry("7.6.0.1", 3), entry("7.4.2.0", 1)));
        farm.protocol = rng.chance(0.8) ? "IoT" : "NoIoT";
        farm.contractUser = rng.pick(List.of("Bryce", "Anna", "Lars", "Sophie", "Tom", "Eva", "Liam")) + " " + owner;
        farm.animalsOnFarm = switch (tenant.specie()) {
          case "Poultry" -> rng.nextInt(8, 60) * 1000;
          case "Feedlot" -> rng.nextInt(400, 6000);
          default -> rng.nextInt(80, 650);
        };
        farm.address = story ? "Drachten" : rng.pick(country.towns());
        farm.country = country.name();
        farm.latitude = round4(country.lat() + (rng.next() - 0.5) * 2.2);
        farm.longitude = round4(country.lng() + (rng.next() - 0.5) * 3.0);
        farm.registeredAt = at.apply(rng.nextInt(200, 2400), 0.0);
        farms.add(farm);
      }
    }

    // Story 1: the 5.16.0 rollout wave — Hoeve De Linde plus 13 more production dairy and poultry farms.
    Set<String> rolloutFarms = new LinkedHashSet<>(List.of("TestFarmId0"));
    List<Farm> candidates = farms.stream()
        .filter(f -> f.environment.equals("Production") && !f.specie.equals("Feedlot") && !f.id.equals("TestFarmId0"))
        .toList();
    while (rolloutFarms.size() < 14) rolloutFarms.add(rng.pick(candidates).id);
    for (Farm farm : farms) if (rolloutFarms.contains(farm.id)) farm.lastUpgradeAt = rollout;

    // Devices -------------------------------------------------------------------------------------------------
    List<Device> devices = new ArrayList<>();
    Map<String, List<Device>> devicesByFarm = new LinkedHashMap<>();
    for (Farm farm : farms) {
      int count = farm.id.equals("TestFarmId0") ? 72 : rng.nextInt(18, 80);
      List<Device> list = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        String type = i == 0 ? "Gateway" : rng.weighted(DEVICE_TYPES_WITHOUT_GATEWAY.get(farm.specie));
        boolean upgraded = rolloutFarms.contains(farm.id) && (type.equals("Collar") || type.equals("Ear Tag") || type.equals("Climate Sensor"));
        String firmware = upgraded ? NEW_FIRMWARE : rng.chance(0.3) ? "5.14.2" : "5.15.0";

        boolean flatTags = isBatteryStory(farm) && type.equals("Ear Tag") && rng.chance(0.55);
        int battery = flatTags ? rng.nextInt(3, 18) : rng.nextInt(35, 100);

        double offlineP = upgraded ? (farm.id.equals("TestFarmId0") ? 0.62 : 0.38) : isGatewayStory(farm) ? 0.09 : 0.035;
        boolean offline = rng.chance(offlineP);
        String status = offline ? "OFFLINE"
            : battery < 20 ? "LOW_BATTERY"
            : type.equals("Collar") && rng.chance(0.03) ? "NOT_ON_COW"
            : "ONLINE";
        Instant lastSeen;
        if (offline && upgraded) {
          int daysAgo = rng.nextInt(0, ROLLOUT_DAYS_AGO - 1);
          lastSeen = at.apply(daysAgo, (double) rng.nextInt(1, 20));
        } else if (offline) {
          int daysAgo = rng.nextInt(1, 9);
          lastSeen = at.apply(daysAgo, (double) rng.nextInt(0, 23));
        } else {
          lastSeen = at.apply(0, 24 - rng.nextInt(1, 50) / 60.0);
        }
        Instant deviceRegistered = at.apply(rng.nextInt(20, 900), 0.0);

        Device device = new Device(
            farm.id.replace("TestFarmId", "DEV-") + "-" + String.format("%03d", i + 1),
            farm.id, type, HARDWARE.get(type), firmware, status, battery, lastSeen,
            farm.registeredAt.isAfter(deviceRegistered) ? farm.registeredAt : deviceRegistered);
        list.add(device);
        devices.add(device);
      }
      devicesByFarm.put(farm.id, list);

      double offlineRatio = list.stream().filter(d -> d.status().equals("OFFLINE")).count() / (double) list.size();
      farm.connectivityStatus = offlineRatio >= 0.5 ? "OFFLINE" : offlineRatio >= 0.2 ? "PARTIALLY_ONLINE" : offlineRatio >= 0.08 ? "WARNING" : "ONLINE";
      if (!farm.environment.equals("Production") && rng.chance(0.15)) farm.connectivityStatus = "UNKNOWN";
    }

    // Connectivity snapshots: 60 days per farm, the last one agreeing with the devices' current status ---------
    List<Snapshot> snapshots = new ArrayList<>();
    for (Farm farm : farms) {
      List<Device> list = devicesByFarm.get(farm.id);
      int total = list.size();
      int offlineNow = (int) list.stream().filter(d -> d.status().equals("OFFLINE")).count();
      int baseline = Math.max(0, (int) Math.round(total * 0.035));
      for (int daysAgo = DAYS - 1; daysAgo >= 0; daysAgo--) {
        int offline;
        if (daysAgo == 0) {
          offline = offlineNow;
        } else if (rolloutFarms.contains(farm.id) && daysAgo <= ROLLOUT_DAYS_AGO) {
          double ramp = Math.min(1, (ROLLOUT_DAYS_AGO - daysAgo + 1) / 3.0);
          offline = (int) Math.round(baseline + Math.max(0, offlineNow - baseline) * ramp * (0.85 + rng.next() * 0.3));
        } else if (isGatewayStory(farm) && daysAgo <= 7) {
          offline = (int) Math.round(total * (0.06 + rng.next() * 0.06));
        } else {
          offline = Math.max(0, baseline + rng.nextInt(-1, 2));
        }
        offline = Math.min(total, Math.max(0, offline));
        LocalDate day = LocalDate.ofInstant(at.apply(daysAgo, 0.0), ZoneOffset.UTC);
        snapshots.add(new Snapshot(farm.id, day, total - offline, offline, total));
      }
    }

    // Issues ----------------------------------------------------------------------------------------------------
    List<Issue> issues = new ArrayList<>();
    List<Map.Entry<Definition, Integer>> weights = DEFINITIONS.stream()
        .filter(d -> !d.id().equals("def-firmware"))
        .map(d -> entry(d, d.weight()))
        .toList();

    IssueRaiser raise = (farm, defId, daysAgo, resource) -> {
      Definition def = DEFINITIONS.stream().filter(d -> d.id().equals(defId)).findFirst().orElseThrow();
      Device device = resource != null ? resource : rng.pick(devicesByFarm.get(farm.id));
      int hour = rng.nextInt(0, 23);
      Instant created = at.apply(daysAgo, hour + rng.next());
      double ageDays = (anchorMs - created.toEpochMilli()) / (double) DAY_MS;
      int[] hours = RESOLUTION_HOURS.get(def.severity());
      boolean resolves = rng.chance(ageDays > 3 ? 0.92 : ageDays > 1 ? 0.6 : 0.3);
      Instant resolved = resolves ? created.plusMillis(rng.nextInt(hours[0], hours[1]) * 3_600_000L) : null;
      if (resolved != null && resolved.isAfter(anchor)) resolved = null;
      issues.add(new Issue(
          "ISS-" + String.format("%06d", issues.size() + 1), def.id(), farm.tenantId, farm.id, device.id(), def.severity(),
          resolved != null ? "CLOSED" : "OPEN", created, resolved));
    };

    for (Farm farm : farms) {
      List<Device> list = devicesByFarm.get(farm.id);
      List<Device> upgraded = list.stream().filter(d -> d.firmwareVersion().equals(NEW_FIRMWARE)).toList();
      double lambda = 0.12 + list.size() / 500.0;
      for (int daysAgo = DAYS - 1; daysAgo >= 0; daysAgo--) {
        for (int n = rng.poisson(lambda); n > 0; n--) raise.raise(farm, rng.weighted(weights).id(), daysAgo, null);

        if (rolloutFarms.contains(farm.id) && daysAgo <= ROLLOUT_DAYS_AGO) {
          double hit = farm.id.equals("TestFarmId0") ? 1.8 : 1;
          for (int n = rng.poisson(0.9 * hit); n > 0; n--) raise.raise(farm, "def-firmware", daysAgo, rng.pick(upgraded));
          for (int n = rng.poisson(2.2 * hit); n > 0; n--) raise.raise(farm, "def-heartbeat", daysAgo, rng.pick(upgraded));
          for (int n = rng.poisson(1.4 * hit); n > 0; n--) raise.raise(farm, "def-telemetry", daysAgo, rng.pick(upgraded));
        }
        if (isGatewayStory(farm)) {
          double rate = daysAgo <= 6 ? 1.1 : daysAgo <= 13 ? 0.27 : 0.2;
          for (int n = rng.poisson(rate); n > 0; n--) raise.raise(farm, "def-gateway", daysAgo, list.get(0));
        }
        if (isBatteryStory(farm)) {
          double rate = daysAgo <= 14 ? 1.6 : 0.5;
          List<Device> flat = list.stream().filter(d -> d.batteryPct() < 20).toList();
          for (int n = rng.poisson(rate); n > 0 && !flat.isEmpty(); n--) raise.raise(farm, "def-battery", daysAgo, rng.pick(flat));
        }
      }
    }

    return new Estate(anchor, farms, devices, issues, snapshots);
  }

  @FunctionalInterface
  private interface IssueRaiser {
    void raise(Farm farm, String definitionId, int daysAgo, Device resource);
  }

  private static boolean isGatewayStory(Farm f) {
    return f.countryCode.equals("DE") && f.specie.equals("Dairy") && f.environment.equals("Production");
  }

  private static boolean isBatteryStory(Farm f) {
    return f.countryCode.equals("IE") && f.specie.equals("Feedlot");
  }

  private static double round4(double value) {
    return Math.round(value * 10_000) / 10_000.0;
  }

  public static Instant startOfTodayUtc() {
    return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
  }
}
