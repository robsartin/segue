package com.robsartin.segue.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The colour-blindness and contrast arithmetic behind {@code DotWriter}'s fills, re-run on every
 * build instead of trusted to a comment.
 *
 * <p>Issue #59 chose the six kind fills by simulating protanopia, deuteranopia and tritanopia
 * (Machado, Oliveira and Fernandes 2009, severity 1.0) and scoring the worst CIELAB distance over
 * every pair. Issue #63 adds four shades of the WORK yellow, which is exactly the change that could
 * quietly undo that work — a dark enough yellow approaches the tinted orange GROUP wears. So the
 * method is reproduced here and the guarantees are asserted rather than restated:
 *
 * <ul>
 *   <li>no shade is closer to another kind's fill than the palette's own worst pair already is, so
 *       shading adds no confusable pair that was not there before;
 *   <li>the yellows stay far enough apart from each other to be worth having;
 *   <li>black labels stay at WCAG AAA on every fill, shades included, because {@code style=filled}
 *       puts the label on top of the fill.
 * </ul>
 *
 * <p>The fills are read out of rendered DOT rather than duplicated here: this test asserts about
 * what the writer actually emits.
 */
class PaletteSeparationTest {

  /** Machado et al. (2009), severity 1.0, applied in linear light. */
  private static final Map<String, double[][]> DEFICIENCIES =
      Map.of(
          "protanopia",
              new double[][] {
                {0.152286, 1.052583, -0.204868},
                {0.114503, 0.786281, 0.099216},
                {-0.003882, -0.048116, 1.051998}
              },
          "deuteranopia",
              new double[][] {
                {0.367322, 0.860646, -0.227968},
                {0.280085, 0.672501, 0.047413},
                {-0.011820, 0.042940, 0.968881}
              },
          "tritanopia",
              new double[][] {
                {1.255528, -0.076749, -0.178779},
                {-0.078411, 0.930809, 0.147602},
                {0.004733, 0.691367, 0.303900}
              });

  /** WCAG AAA for text on a background. Black labels sit on top of every one of these fills. */
  private static final double AAA = 7.0;

  /** The four WORK classes that have a shade of their own, by the name the tooltip gives them. */
  private static final Map<String, String> SHADED_WORK_CLASSES =
      Map.of(
          "Q482994", "album",
          "Q105543609", "musical work/composition",
          "Q134556", "single",
          "Q11424", "film");

  @Test
  @DisplayName("black labels stay AAA on every fill, shades included")
  void keepsBlackLabelsAtAaaOnEveryFill() throws IOException {
    for (Map.Entry<String, String> fill : allFills().entrySet()) {
      assertThat(contrastWithBlack(fill.getValue()))
          .as("black on %s (%s)", fill.getKey(), fill.getValue())
          .isGreaterThanOrEqualTo(AAA);
    }
  }

  @Test
  @DisplayName("no WORK shade is closer to another kind than the palette's own worst pair")
  void addsNoConfusablePairAcrossKinds() throws IOException {
    Map<String, String> kinds = kindFills();
    double floor = worstPair(kinds).distance();

    List<Pair> offenders = new ArrayList<>();
    for (Map.Entry<String, String> shade : shadeFills().entrySet()) {
      for (Map.Entry<String, String> kind : kinds.entrySet()) {
        if (kind.getKey().equals(NodeKind.WORK.name())) {
          continue; // a shade is a WORK, so this pair is not a cross-kind pair at all
        }
        Pair pair = worstOf(shade.getKey(), shade.getValue(), kind.getKey(), kind.getValue());
        if (pair.distance() < floor) {
          offenders.add(pair);
        }
      }
    }

    assertThat(offenders)
        .as("pairs closer than the existing worst (%s, ΔE %.2f)", worstPair(kinds), floor)
        .isEmpty();
  }

  @Test
  @DisplayName("the five yellows stay far enough apart to be worth shading at all")
  void keepsTheYellowsApartFromEachOther() throws IOException {
    Map<String, String> yellows = new LinkedHashMap<>(shadeFills());
    yellows.put("WORK (plain)", kindFills().get(NodeKind.WORK.name()));

    Pair worst = worstPair(yellows);

    assertThat(worst.distance()).as("the closest two yellows: %s", worst).isGreaterThan(8.0);
    assertThat(yellows.values().stream().distinct().count()).isEqualTo(yellows.size());
  }

  // ---- what the writer emits --------------------------------------------

  private static Map<String, String> allFills() throws IOException {
    Map<String, String> fills = new LinkedHashMap<>(kindFills());
    fills.putAll(shadeFills());
    return fills;
  }

  private static Map<String, String> kindFills() throws IOException {
    Map<String, String> fills = new LinkedHashMap<>();
    for (NodeKind kind : NodeKind.values()) {
      fills.put(kind.name(), fillOf(new ViewNode("Q0900901", kind, "x")));
    }
    return fills;
  }

  private static Map<String, String> shadeFills() throws IOException {
    Map<String, String> fills = new LinkedHashMap<>();
    for (Map.Entry<String, String> shaded : SHADED_WORK_CLASSES.entrySet()) {
      fills.put(
          "WORK/" + shaded.getValue(),
          fillOf(new ViewNode("Q0900901", NodeKind.WORK, "x", List.of(shaded.getKey()))));
    }
    return fills;
  }

  private static String fillOf(ViewNode node) throws IOException {
    StringWriter out = new StringWriter();
    new DotWriter().write(new GraphView("a made-up view", List.of(node), List.of()), out);
    Matcher matcher = Pattern.compile("fillcolor=\"(#[0-9A-Fa-f]{6})\"").matcher(out.toString());
    assertThat(matcher.find()).as("a fill for %s", node).isTrue();
    return matcher.group(1);
  }

  // ---- the arithmetic ---------------------------------------------------

  private record Pair(String a, String b, String deficiency, double distance) {
    @Override
    public String toString() {
      return "%s / %s at ΔE %.2f under %s".formatted(a, b, distance, deficiency);
    }
  }

  /** The closest pair in a palette, under the worst of normal vision and the three simulations. */
  private static Pair worstPair(Map<String, String> palette) {
    List<Map.Entry<String, String>> entries = List.copyOf(palette.entrySet());
    Pair worst = null;
    for (int i = 0; i < entries.size(); i++) {
      for (int j = i + 1; j < entries.size(); j++) {
        Pair pair =
            worstOf(
                entries.get(i).getKey(), entries.get(i).getValue(),
                entries.get(j).getKey(), entries.get(j).getValue());
        if (worst == null || pair.distance() < worst.distance()) {
          worst = pair;
        }
      }
    }
    return worst;
  }

  /** How near two fills come under any of the three deficiencies, or none of them. */
  private static Pair worstOf(String nameA, String hexA, String nameB, String hexB) {
    Pair worst = new Pair(nameA, nameB, "normal vision", deltaE(lab(hexA, null), lab(hexB, null)));
    for (Map.Entry<String, double[][]> deficiency : DEFICIENCIES.entrySet()) {
      double distance = deltaE(lab(hexA, deficiency.getValue()), lab(hexB, deficiency.getValue()));
      if (distance < worst.distance()) {
        worst = new Pair(nameA, nameB, deficiency.getKey(), distance);
      }
    }
    return worst;
  }

  private static double contrastWithBlack(String hex) {
    double[] linear = toLinear(hex);
    double luminance = 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    return (luminance + 0.05) / 0.05;
  }

  private static double[] toLinear(String hex) {
    double[] linear = new double[3];
    for (int channel = 0; channel < 3; channel++) {
      double value = Integer.parseInt(hex.substring(1 + channel * 2, 3 + channel * 2), 16) / 255.0;
      linear[channel] = value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
    return linear;
  }

  /** CIELAB (D65) of a fill, optionally as one of the three deficiencies sees it. */
  private static double[] lab(String hex, double[][] simulation) {
    double[] linear = toLinear(hex);
    if (simulation != null) {
      double[] seen = new double[3];
      for (int row = 0; row < 3; row++) {
        seen[row] =
            simulation[row][0] * linear[0]
                + simulation[row][1] * linear[1]
                + simulation[row][2] * linear[2];
      }
      linear = seen;
    }
    double x = 0.4124564 * linear[0] + 0.3575761 * linear[1] + 0.1804375 * linear[2];
    double y = 0.2126729 * linear[0] + 0.7151522 * linear[1] + 0.0721750 * linear[2];
    double z = 0.0193339 * linear[0] + 0.1191920 * linear[1] + 0.9503041 * linear[2];
    double fx = f(x / 0.95047);
    double fy = f(y);
    double fz = f(z / 1.08883);
    return new double[] {116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
  }

  private static double f(double t) {
    return t > 216.0 / 24389.0 ? Math.cbrt(t) : (841.0 / 108.0) * t + 4.0 / 29.0;
  }

  private static double deltaE(double[] a, double[] b) {
    return Math.sqrt(
        Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
  }
}
