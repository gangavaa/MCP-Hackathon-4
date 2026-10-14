package com.nms.dashboard.mcp;

import com.nms.dashboard.mcp.api.ChartData;
import com.nms.dashboard.mcp.api.QueryColumn;
import com.nms.dashboard.mcp.api.QueryResult;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.RingPlot;
import org.jfree.chart.renderer.category.AreaRenderer;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.LineAndShapeRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.chart.title.LegendTitle;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DefaultPieDataset;
import org.springframework.stereotype.Component;

/**
 * The chart as a PNG, drawn in this process. Hosts that cannot frame the MCP App panel — Claude Desktop behind a
 * proxy that intercepts its sandbox domain — still show an image block natively. Colours are the panel's (MSD's
 * teal-led palette, the NMS severity and status tokens), so the picture matches the interactive chart.
 */
@Component
public class ChartImageRenderer {

  static final int WIDTH = 900;
  static final int HEIGHT = 500;
  static final int SCALE = 2;

  private static final Color INK = new Color(0x1f2937);
  private static final Color MUTED = new Color(0x637085);
  private static final Color GRID = new Color(0xe5e7eb);
  private static final Color HEADER = new Color(0x00857c); // MSD Teal
  private static final Color ACCENT = new Color(0x00857c);
  private static final Color PREVIOUS = new Color(0xb8c4d3);
  private static final Color[] PALETTE = {
      new Color(0x00857c), new Color(0x6eceb2), new Color(0x005c55), new Color(0x0c2340), new Color(0xbfed33),
      new Color(0x69b8f7), new Color(0x5450e4), new Color(0x7a8c8a), new Color(0xa6dcd6)};
  private static final Map<String, Color> SEMANTIC = Map.ofEntries(
      Map.entry("CRITICAL", new Color(0xf87171)), Map.entry("HIGH", new Color(0xfb923c)), Map.entry("MEDIUM", new Color(0xfbbf24)),
      Map.entry("LOW", new Color(0x6eceb2)), Map.entry("ONLINE", new Color(0x00857c)), Map.entry("OFFLINE", new Color(0xd84c57)),
      Map.entry("WARNING", new Color(0xc88849)), Map.entry("PARTIALLY_ONLINE", new Color(0xa1a131)), Map.entry("UNKNOWN", new Color(0x707070)),
      Map.entry("LOW_BATTERY", new Color(0xc88849)), Map.entry("NOT_ON_COW", new Color(0x7048cc)), Map.entry("OPEN", new Color(0xd84c57)),
      Map.entry("CLOSED", new Color(0x7a8c8a)), Map.entry("5.16.0", new Color(0xd84c57)));

  private static final String FONT = "Helvetica";
  private static final DecimalFormat NUMBER = new DecimalFormat("#,##0.#");

  public byte[] render(ChartData data) {
    String type = Objects.requireNonNullElse(data.spec().chartType(), "bar");
    String insight = data.spec().insight();
    int top = insight != null && !insight.isBlank() ? 100 : 80;
    int height = switch (type) {
      case "kpi" -> 330;
      case "table" -> Math.min(HEIGHT, top + 50 + Math.min(data.result().rows().size(), 12) * 28);
      default -> HEIGHT;
    };

    BufferedImage image = new BufferedImage(WIDTH * SCALE, height * SCALE, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    g.scale(SCALE, SCALE);
    g.setColor(Color.WHITE);
    g.fill(new RoundRectangle2D.Double(0, 0, WIDTH, height, 24, 24));
    header(g, data.spec().title(), insight);

    Rectangle2D area = new Rectangle2D.Double(16, top, WIDTH - 32, height - top - 12);
    switch (type) {
      case "kpi" -> kpi(g, data, area);
      case "table" -> table(g, data.result(), area);
      default -> chart(data, type).draw(g, area);
    }
    g.dispose();

    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("PNG encoding failed", e);
    }
  }

  // ------------------------------------------------------------------------------------------- header

  private static void header(Graphics2D g, String title, String insight) {
    g.setColor(HEADER);
    g.fill(new RoundRectangle2D.Double(24, 22, 34, 16, 6, 6));
    g.setColor(Color.WHITE);
    g.setFont(new Font(FONT, Font.BOLD, 10));
    centered(g, "NMS", 41, 34);
    g.setColor(MUTED);
    g.setFont(new Font(FONT, Font.BOLD, 11));
    g.drawString("DASHBOARD", 66, 34);
    g.setColor(INK);
    g.setFont(new Font(FONT, Font.BOLD, 20));
    g.drawString(Objects.requireNonNullElse(title, ""), 24, 62);
    if (insight != null && !insight.isBlank()) {
      g.setColor(ACCENT);
      g.fillRect(24, 72, 3, 18);
      g.setColor(INK);
      g.setFont(new Font(FONT, Font.PLAIN, 13));
      g.drawString(clip(insight, 120), 34, 86);
    }
  }

  // ------------------------------------------------------------------------------------------- charts

  private record Model(String categoryKey, List<String> categories, String metricKey, String metricLabel,
      List<Series> series, boolean isTime) {}

  private record Series(String name, List<Number> data, Color color, boolean previous) {}

  /** The same shaping as the panel's toModel (frontend shared/render): category axis, then series. */
  private static Model model(ChartData data) {
    QueryResult result = data.result();
    List<QueryColumn> dims = result.columns().stream().filter(c -> c.kind().equals("dimension")).toList();
    QueryColumn metric = result.columns().stream().filter(c -> c.kind().equals("metric")).findFirst().orElseThrow();
    Integer days = data.spec().query().timeRange() == null ? null : data.spec().query().timeRange().lastDays();

    if (dims.isEmpty()) {
      List<Series> series = new ArrayList<>(List.of(new Series(metric.label(), List.of(num(first(result, metric.key()))), PALETTE[0], false)));
      if (data.previous() != null) series.add(new Series(previousLabel(days), List.of(num(first(data.previous(), metric.key()))), PREVIOUS, true));
      return new Model(null, List.of(metric.label()), metric.key(), metric.label(), series, false);
    }

    QueryColumn cat = dims.get(0);
    boolean isTime = cat.key().equals("period");
    List<String> categories = new ArrayList<>(new LinkedHashSet<>(result.rows().stream().map(r -> String.valueOf(r.get(cat.key()))).toList()));
    List<Series> series = new ArrayList<>();

    if (dims.size() > 1) {
      QueryColumn split = dims.get(1);
      List<String> names = new ArrayList<>(new LinkedHashSet<>(result.rows().stream().map(r -> String.valueOf(r.get(split.key()))).toList()));
      for (int i = 0; i < names.size(); i++) {
        String name = names.get(i);
        List<Number> values = categories.stream().map(c -> (Number) result.rows().stream()
            .filter(r -> String.valueOf(r.get(cat.key())).equals(c) && String.valueOf(r.get(split.key())).equals(name))
            .findFirst().map(r -> num(r.get(metric.key()))).orElse(0)).toList();
        series.add(new Series(humanize(name), values, SEMANTIC.getOrDefault(name, PALETTE[i % PALETTE.length]), false));
      }
      return new Model(cat.key(), categories, metric.key(), metric.label(), series, isTime);
    }

    series.add(new Series(data.previous() != null ? currentLabel(days) : metric.label(),
        categories.stream().map(c -> valueAt(result, cat.key(), c, metric.key())).toList(), PALETTE[0], false));
    if (data.previous() != null) {
      QueryResult prev = data.previous();
      List<Number> prevData = new ArrayList<>();
      for (int i = 0; i < categories.size(); i++) {
        prevData.add(isTime ? (i < prev.rows().size() ? num(prev.rows().get(i).get(metric.key())) : null)
            : Objects.requireNonNullElse(valueAt(prev, cat.key(), categories.get(i), metric.key()), 0));
      }
      series.add(new Series(previousLabel(days), prevData, PREVIOUS, true));
    }
    return new Model(cat.key(), categories, metric.key(), metric.label(), series, isTime);
  }

  private static JFreeChart chart(ChartData data, String type) {
    Model model = model(data);
    if (type.equals("pie") || type.equals("donut")) return pie(model, type.equals("donut"));

    boolean horizontal = type.equals("hbar");
    DefaultCategoryDataset dataset = new DefaultCategoryDataset();
    for (Series s : model.series()) {
      for (int i = 0; i < model.categories().size(); i++) dataset.addValue(s.data().get(i), s.name(), humanize(model.categories().get(i)));
    }

    CategoryAxis categoryAxis = new CategoryAxis();
    categoryAxis.setTickLabelFont(new Font(FONT, Font.PLAIN, 12));
    categoryAxis.setTickLabelPaint(MUTED);
    categoryAxis.setAxisLinePaint(GRID);
    categoryAxis.setTickMarksVisible(false);
    categoryAxis.setCategoryMargin(horizontal ? 0.25 : 0.3);
    categoryAxis.setMaximumCategoryLabelWidthRatio(horizontal ? 0.35f : 1f);
    if (model.isTime() && model.categories().size() > 14) categoryAxis.setCategoryLabelPositions(CategoryLabelPositions.UP_45);
    if (type.equals("line") || type.equals("area")) {
      // A trend is one continuous shape: no gaps between days.
      categoryAxis.setCategoryMargin(0);
      categoryAxis.setLowerMargin(0.01);
      categoryAxis.setUpperMargin(0.01);
    }
    NumberAxis valueAxis = new NumberAxis();
    valueAxis.setTickLabelFont(new Font(FONT, Font.PLAIN, 12));
    valueAxis.setTickLabelPaint(MUTED);
    valueAxis.setAxisLineVisible(false);
    valueAxis.setTickMarksVisible(false);
    valueAxis.setNumberFormatOverride(NUMBER);
    valueAxis.setTickUnit(new org.jfree.chart.axis.NumberTickUnit(niceStep(max(model) / 5)));

    CategoryPlot plot = new CategoryPlot(dataset, categoryAxis, valueAxis, null);
    plot.setOrientation(horizontal ? PlotOrientation.HORIZONTAL : PlotOrientation.VERTICAL);
    plot.setBackgroundPaint(Color.WHITE);
    plot.setOutlineVisible(false);
    plot.setRangeGridlinePaint(GRID);
    plot.setRangeGridlineStroke(new BasicStroke(1f));
    plot.setDomainGridlinesVisible(false);
    plot.setAxisOffset(RectangleInsets.ZERO_INSETS);

    boolean single = model.series().size() == 1;
    if (type.equals("line") || type.equals("area")) {
      if (type.equals("area")) {
        AreaRenderer area = new AreaRenderer();
        area.setEndType(org.jfree.chart.renderer.AreaRendererEndType.TRUNCATE); // square edges, not tapered to zero
        for (int i = 0; i < model.series().size(); i++) area.setSeriesPaint(i, translucent(model.series().get(i).color(), model.series().get(i).previous() ? 60 : 50));
        plot.setDataset(1, dataset);
        plot.setRenderer(1, area);
        plot.setDatasetRenderingOrder(org.jfree.chart.plot.DatasetRenderingOrder.REVERSE); // fill beneath the line
      }
      LineAndShapeRenderer line = new LineAndShapeRenderer(true, model.categories().size() < 20);
      for (int i = 0; i < model.series().size(); i++) {
        Series s = model.series().get(i);
        line.setSeriesPaint(i, s.color());
        line.setSeriesStroke(i, s.previous()
            ? new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {6f, 5f}, 0f)
            : new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      }
      plot.setRenderer(0, line);
    } else {
      BarRenderer bar = new BarRenderer() {
        @Override
        public java.awt.Paint getItemPaint(int row, int column) {
          // One series: a value with a meaning keeps its colour (OFFLINE red, 5.16.0 red); the rest share the brand blue.
          if (single) return SEMANTIC.getOrDefault(model.categories().get(column), model.series().get(0).color());
          return model.series().get(row).color();
        }
      };
      bar.setBarPainter(new StandardBarPainter());
      bar.setShadowVisible(false);
      bar.setDrawBarOutline(false);
      bar.setItemMargin(0.08);
      bar.setMaximumBarWidth(0.12);
      for (int i = 0; i < model.series().size(); i++) bar.setSeriesPaint(i, model.series().get(i).color());
      plot.setRenderer(bar);
    }

    JFreeChart chart = new JFreeChart(null, new Font(FONT, Font.PLAIN, 12), plot, model.series().size() > 1);
    chart.setBackgroundPaint(Color.WHITE);
    chart.setPadding(new RectangleInsets(4, 4, 4, 12));
    styleLegend(chart.getLegend());
    return chart;
  }

  private static JFreeChart pie(Model model, boolean donut) {
    DefaultPieDataset<String> dataset = new DefaultPieDataset<>();
    Series s = model.series().get(0);
    for (int i = 0; i < model.categories().size(); i++) dataset.setValue(humanize(model.categories().get(i)), s.data().get(i));
    @SuppressWarnings({"unchecked", "rawtypes"}) // RingPlot is not generic in JFreeChart 1.5
    PiePlot<String> plot = donut ? (PiePlot<String>) (PiePlot) new RingPlot(dataset) : new PiePlot<>(dataset);
    if (plot instanceof RingPlot ring) {
      ring.setSectionDepth(0.38);
      ring.setSeparatorsVisible(false);
    }
    for (int i = 0; i < model.categories().size(); i++) {
      String raw = model.categories().get(i);
      plot.setSectionPaint(humanize(raw), SEMANTIC.getOrDefault(raw, PALETTE[i % PALETTE.length]));
    }
    plot.setBackgroundPaint(Color.WHITE);
    plot.setOutlineVisible(false);
    plot.setShadowPaint(null);
    plot.setDefaultSectionOutlinePaint(Color.WHITE);
    plot.setDefaultSectionOutlineStroke(new BasicStroke(2f));
    plot.setLabelFont(new Font(FONT, Font.PLAIN, 12));
    plot.setLabelPaint(INK);
    plot.setLabelBackgroundPaint(Color.WHITE);
    plot.setLabelOutlinePaint(null);
    plot.setLabelShadowPaint(null);
    plot.setLabelLinkPaint(MUTED);
    plot.setLabelGenerator(new org.jfree.chart.labels.StandardPieSectionLabelGenerator("{0}: {2}", NUMBER, new DecimalFormat("0.0%")));
    JFreeChart chart = new JFreeChart(null, new Font(FONT, Font.PLAIN, 12), plot, true);
    chart.setBackgroundPaint(Color.WHITE);
    styleLegend(chart.getLegend());
    return chart;
  }

  private static void styleLegend(LegendTitle legend) {
    if (legend == null) return;
    legend.setPosition(RectangleEdge.BOTTOM);
    legend.setFrame(org.jfree.chart.block.BlockBorder.NONE);
    legend.setItemFont(new Font(FONT, Font.PLAIN, 12));
    legend.setItemPaint(MUTED);
    legend.setBackgroundPaint(Color.WHITE);
  }

  // ------------------------------------------------------------------------------------------- kpi, table

  private static void kpi(Graphics2D g, ChartData data, Rectangle2D area) {
    Model model = model(data);
    double current = sum(model.series().get(0).data());
    int x = (int) area.getX() + 10;
    int y = (int) area.getY();
    g.setColor(HEADER);
    g.setFont(new Font(FONT, Font.BOLD, 96));
    g.drawString(NUMBER.format(current), x, y + 100);
    g.setColor(MUTED);
    g.setFont(new Font(FONT, Font.PLAIN, 20));
    g.drawString(model.metricLabel(), x + 2, y + 140);
    if (data.previous() != null && model.series().size() > 1) {
      double previous = sum(model.series().get(1).data());
      if (previous != 0) {
        double pct = (current - previous) / previous * 100;
        g.setColor(pct > 0 ? new Color(0xd84c57) : new Color(0x31c446)); // every metric here counts a problem: up is bad
        g.setFont(new Font(FONT, Font.BOLD, 22));
        g.drawString((pct > 0 ? "▲ " : "▼ ") + NUMBER.format(Math.abs(pct)) + "% vs previous period", x + 2, y + 180);
      }
    }
  }

  private static void table(Graphics2D g, QueryResult result, Rectangle2D area) {
    List<QueryColumn> columns = result.columns();
    double colWidth = area.getWidth() / columns.size();
    int x0 = (int) area.getX();
    int y = (int) area.getY() + 20;
    g.setFont(new Font(FONT, Font.BOLD, 14));
    g.setColor(MUTED);
    for (int i = 0; i < columns.size(); i++) cell(g, columns.get(i).label(), x0, i, colWidth, y, columns.get(i).kind().equals("metric"));
    g.setFont(new Font(FONT, Font.PLAIN, 14));
    List<Map<String, Object>> rows = result.rows().subList(0, Math.min(12, result.rows().size()));
    for (Map<String, Object> row : rows) {
      y += 28;
      g.setColor(GRID);
      g.drawLine(x0, y - 19, (int) (x0 + area.getWidth()), y - 19);
      g.setColor(INK);
      for (int i = 0; i < columns.size(); i++) {
        QueryColumn c = columns.get(i);
        Object v = row.get(c.key());
        cell(g, c.kind().equals("metric") ? format(v) : humanize(v), x0, i, colWidth, y, c.kind().equals("metric"));
      }
    }
    if (result.rows().size() > rows.size()) {
      g.setColor(MUTED);
      g.drawString("… " + (result.rows().size() - rows.size()) + " more rows", x0 + 8, y + 28);
    }
  }

  private static void cell(Graphics2D g, String text, int x0, int col, double colWidth, int y, boolean numeric) {
    String clipped = clip(text, 40);
    int x = numeric ? (int) (x0 + (col + 1) * colWidth - 12 - g.getFontMetrics().stringWidth(clipped)) : (int) (x0 + col * colWidth + 8);
    g.drawString(clipped, x, y);
  }

  // ------------------------------------------------------------------------------------------- helpers

  /** CRITICAL → Critical, 2026-10-05 → 5 Oct; everything else as is. Mirrors the panel's humanize. */
  static String humanize(Object value) {
    if (value == null || "".equals(value)) return "—";
    String text = String.valueOf(value);
    if (text.matches("[A-Z_]+")) return text.charAt(0) + text.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
    if (text.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(text).format(DateTimeFormatter.ofPattern("d MMM", Locale.UK));
    return text;
  }

  static String format(Object value) {
    return value instanceof Number n ? NUMBER.format(n) : value == null ? "—" : String.valueOf(value);
  }

  private static String currentLabel(Integer days) {
    return days == null ? "Current" : days == 7 ? "This week" : "Last " + days + " days";
  }

  private static String previousLabel(Integer days) {
    return days == null ? "Previous" : days == 7 ? "Week before" : "Previous " + days + " days";
  }

  private static Object first(QueryResult result, String key) {
    return result.rows().isEmpty() ? null : result.rows().get(0).get(key);
  }

  private static Number valueAt(QueryResult result, String catKey, String category, String metricKey) {
    return result.rows().stream().filter(r -> String.valueOf(r.get(catKey)).equals(category)).findFirst().map(r -> num(r.get(metricKey))).orElse(null);
  }

  private static Number num(Object value) {
    return value instanceof Number n ? n : value == null ? null : Double.valueOf(String.valueOf(value));
  }

  private static double sum(List<Number> values) {
    return values.stream().filter(Objects::nonNull).mapToDouble(Number::doubleValue).sum();
  }

  private static double max(Model model) {
    return model.series().stream().flatMap(s -> s.data().stream()).filter(Objects::nonNull).mapToDouble(Number::doubleValue).max().orElse(1);
  }

  /** 1, 2, 2.5 or 5 times a power of ten — the steps a person would mark an axis in. */
  static double niceStep(double rough) {
    if (rough <= 0) return 1;
    double power = Math.pow(10, Math.floor(Math.log10(rough)));
    double f = rough / power;
    return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * power;
  }

  private static Color translucent(Color c, int alpha) {
    return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
  }

  private static void centered(Graphics2D g, String text, int cx, int y) {
    g.drawString(text, cx - g.getFontMetrics().stringWidth(text) / 2, y);
  }

  private static String clip(String text, int max) {
    return text.length() > max ? text.substring(0, max - 1) + "…" : text;
  }
}
