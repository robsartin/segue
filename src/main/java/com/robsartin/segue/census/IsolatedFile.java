package com.robsartin.segue.census;

import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.SecondHop;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The isolated acts, as a file the owner can read (#319).
 *
 * <p><b>This is personal data and the census block is not.</b> It holds entity ids and labels off
 * the owner's own list, which is exactly what {@code CensusReport} exists never to print — so
 * {@code CensusIsSafeToPasteTest}'s discipline applies to the terminal report and <b>not</b> to
 * this file, and must not be added here by analogy. {@code ratings.NamesFile} carries the same note
 * for the same reason.
 *
 * <p><b>Tab-separated, not CSV.</b> Labels contain commas, and this is a listing to read rather
 * than a table to load — the call {@code RatingsTable} already made in <i>Plain text rather than
 * CSV</i>.
 *
 * <p><b>The population's own order, and no sort.</b> The file's order and then the promotions
 * ascending by qid, as {@code KnownList.promoted} gives it. A second ordering rule would be a
 * second thing to keep in step with that one (#313's reasoning), and this file is for reading
 * rather than for diffing.
 *
 * <p><b>An act the graph holds no label for is written as its qid</b>, not skipped and not written
 * as a phrase. {@code NamesFile}'s reasoning exactly: the qid identifies the row and is wrong in an
 * obvious way rather than a quiet one.
 */
final class IsolatedFile {

  /** Said on the first line of every file this writes, followed by the count on the same line. */
  static final String PERSONAL_DATA_HEADER =
      "# segue known-list acts the graph cannot place — personal data under ADR 33 and issue #37."
          + " Keep this file outside the working tree and out of version control: this repository"
          + " is public.";

  private IsolatedFile() {}

  /**
   * Write the header and one act per line: qid, label, kind, and how many unexpanded people and
   * groups are beside it, tab-separated.
   *
   * @return how many of them the graph holds no label for, and so were written as their qid
   */
  static int write(SecondHop rule, Map<String, NodeRecord> nodes, Writer out) throws IOException {
    Objects.requireNonNull(rule, "rule");
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(out, "out");
    List<String> isolated = rule.isolated();
    out.write(PERSONAL_DATA_HEADER);
    out.write(
        " "
            + isolated.size()
            + " act(s), one per line: qid, label, kind, and how many"
            + " unexpanded people and groups are beside it.\n");
    int unnamed = 0;
    for (String qid : isolated) {
      NodeRecord node = nodes.get(qid);
      String label = node.label();
      if (label.isBlank()) {
        label = qid;
        unnamed++;
      }
      out.write(qid);
      out.write('\t');
      out.write(label);
      out.write('\t');
      out.write(node.kind().name());
      out.write('\t');
      out.write(String.valueOf(rule.toExpandBeside(qid).size()));
      out.write('\n');
    }
    return unnamed;
  }
}
