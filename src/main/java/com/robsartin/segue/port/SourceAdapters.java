package com.robsartin.segue.port;

import com.robsartin.segue.domain.Provenance;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The configured set of {@link SourceAdapter}s, as one bean.
 *
 * <p>A bean whose type is {@code List<SourceAdapter>} would collide with Spring's own
 * collection-injection machinery, which gathers every singleton bean assignable to {@code
 * SourceAdapter} into any {@code List<SourceAdapter>} injection point it sees. Wrapping the list in
 * a small holder sidesteps that ambiguity entirely: consumers ask for a {@code SourceAdapters}
 * bean, not a raw collection type Spring might also try to autowire.
 *
 * <p><b>It also checks that the ids identify</b> (<a
 * href="https://github.com/robsartin/segue/issues/148">issue #148</a>). {@code
 * SegueService.expandEntity} names the source of a shortfall by {@link SourceAdapter#id()}, so that
 * string carries weight it did not before: two adapters sharing one would produce a message naming
 * something ambiguous, a blank one names nothing, and a tab or a newline would break the {@code
 * ToolResult} detail and the log line it is put in.
 *
 * <p><b>Every one of those was already forbidden and none was enforced here.</b> {@link
 * SourceAdapter#id()} says it <i>is</i> the {@code sourceId} every assertion the adapter emits will
 * carry, and {@link Provenance}'s constructor refuses a tab or a newline in that field — but only
 * when an assertion is actually emitted. An adapter reporting itself unavailable emits none, which
 * is exactly the case attribution exists for. So this costs a conforming adapter nothing; what
 * changes is that a non-conforming one is refused when the bean is built rather than never.
 */
public record SourceAdapters(List<SourceAdapter> all) {

  public SourceAdapters {
    all = List.copyOf(Objects.requireNonNull(all, "all"));
    Set<String> seen = new HashSet<>();
    for (SourceAdapter adapter : all) {
      String id = Objects.requireNonNull(adapter.id(), "adapter id");
      if (id.isBlank()) {
        throw new IllegalArgumentException("adapter id must not be blank");
      }
      if (id.contains(Provenance.FIELD_SEP) || id.contains(Provenance.RECORD_SEP)) {
        throw new IllegalArgumentException("adapter id must not contain tabs or newlines: " + id);
      }
      if (!seen.add(id)) {
        throw new IllegalArgumentException("two adapters share the id: " + id);
      }
    }
  }
}
