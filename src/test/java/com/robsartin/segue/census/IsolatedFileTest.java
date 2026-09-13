package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Expanded;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.SecondHop;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The four fields, the header, the fallback and the order. Every id and label is invented. */
class IsolatedFileTest {

  /** Isolated, labelled, with one unexpanded PERSON beside it. */
  private static final String ALONE = "Q0901409";

  /** Isolated, and the graph holds a blank label for it. */
  private static final String NAMELESS = "Q0901410";

  /** The unexpanded PERSON beside {@link #ALONE}. */
  private static final String NEXT_TO = "Q0901411";

  private static final String LABEL = "An Act Unlike Anything Real";

  private static Map<String, NodeRecord> nodes() {
    Map<String, NodeRecord> nodes = new LinkedHashMap<>();
    nodes.put(ALONE, new NodeRecord(ALONE, NodeKind.GROUP, LABEL));
    nodes.put(NAMELESS, new NodeRecord(NAMELESS, NodeKind.PERSON, ""));
    nodes.put(NEXT_TO, new NodeRecord(NEXT_TO, NodeKind.PERSON, "A Neighbour"));
    return nodes;
  }

  private static SecondHop rule() {
    return SecondHop.of(
        nodes(),
        List.of(new EdgeRecord(ALONE, NEXT_TO, "MEMBER_OF", null, null, List.of())),
        List.of(ALONE, NAMELESS),
        new Expanded(Set.of()));
  }

  private static List<String> written() throws Exception {
    StringWriter out = new StringWriter();
    IsolatedFile.write(rule(), nodes(), out);
    return List.of(out.toString().split("\n", -1));
  }

  @Test
  @DisplayName("the first line names the file as personal data to keep outside the tree")
  void shouldOpenWithThePersonalDataHeaderWhenTheFileIsWritten() throws Exception {
    assertThat(written().get(0)).startsWith(IsolatedFile.PERSONAL_DATA_HEADER).contains("2 act(s)");
  }

  @Test
  @DisplayName("each act is four tab-separated fields: qid, label, kind and the count beside it")
  void shouldWriteFourTabSeparatedFieldsWhenAnActIsIsolated() throws Exception {
    assertThat(written().get(1))
        .isEqualTo(ALONE + "\t" + LABEL + "\t" + NodeKind.GROUP.name() + "\t1");
  }

  @Test
  @DisplayName("an act the graph holds no label for is written as its qid, never dropped")
  void shouldWriteTheQidAsTheLabelWhenTheGraphHoldsNoneForTheAct() throws Exception {
    assertThat(written().get(2))
        .as("NamesFile's reasoning: wrong in an obvious way rather than a quiet one")
        .isEqualTo(NAMELESS + "\t" + NAMELESS + "\t" + NodeKind.PERSON.name() + "\t0");
    assertThat(IsolatedFile.write(rule(), nodes(), new StringWriter()))
        .as("and the caller is told how many rows that was")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("the acts come out in the population's own order, with no sort of their own")
  void shouldKeepThePopulationsOrderWhenTheActsAreWritten() throws Exception {
    assertThat(written().subList(1, 3))
        .as("ALONE before NAMELESS because the population names them that way")
        .allSatisfy(line -> assertThat(line).isNotBlank());
    assertThat(written().get(1)).startsWith(ALONE);
    assertThat(written().get(2)).startsWith(NAMELESS);
  }

  @Test
  @DisplayName("a label carrying a tab or a newline is written as one line of four fields")
  void shouldFlattenTheLabelWhenItCarriesATabOrANewline() throws Exception {
    // ALONE is reused rather than inventing a fourth id; only its label changes for this test.
    Map<String, NodeRecord> nodes = new LinkedHashMap<>();
    nodes.put(ALONE, new NodeRecord(ALONE, NodeKind.GROUP, "An\tInvented\nAct\r"));
    SecondHop rule = SecondHop.of(nodes, List.of(), List.of(ALONE), new Expanded(Set.of()));

    StringWriter out = new StringWriter();
    IsolatedFile.write(rule, nodes, out);
    List<String> lines = List.of(out.toString().split("\n", -1));

    assertThat(lines)
        .as(
            "the header line plus one line per act, plus the trailing empty split artifact"
                + " after the final newline — never two lines for one act")
        .hasSize(3);
    String[] fields = lines.get(1).split("\t", -1);
    assertThat(fields).as("qid, label, kind and the count, and no more").hasSize(4);
    assertThat(fields[1])
        .as("tab and newline become a space; the trailing space is the flattened CR")
        .isEqualTo("An Invented Act ");
  }
}
