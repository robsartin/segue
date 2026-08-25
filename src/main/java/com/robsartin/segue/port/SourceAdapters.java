package com.robsartin.segue.port;

import java.util.List;

/**
 * The configured set of {@link SourceAdapter}s, as one bean.
 *
 * <p>A bean whose type is {@code List<SourceAdapter>} would collide with Spring's own
 * collection-injection machinery, which gathers every singleton bean assignable to {@code
 * SourceAdapter} into any {@code List<SourceAdapter>} injection point it sees. Wrapping the list in
 * a small holder sidesteps that ambiguity entirely: consumers ask for a {@code SourceAdapters}
 * bean, not a raw collection type Spring might also try to autowire.
 */
public record SourceAdapters(List<SourceAdapter> all) {}
