package com.robsartin.segue.expansion;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wiring both entry points share. It constructs clients and asks nothing of them, so this test
 * opens no socket — see {@code ExpansionSources.both}'s javadoc for why order is the whole point.
 */
class ExpansionSourcesTest {

  @Test
  @DisplayName("Wikidata is asked first and MusicBrainz second, because one bound is shared")
  void shouldAskWikidataFirstWhenBothSourcesAreWired() {
    SourceAdapters adapters =
        ExpansionSources.both(
            new WikidataEntityResolver(new WikidataClient(), Clock.systemUTC()), Clock.systemUTC());

    assertThat(adapters.all().stream().map(a -> a.id()).toList())
        .as(
            "the order is load-bearing: SegueService bounds the concatenation, so a tight bound is"
                + " spent by whichever adapter runs first")
        .isEqualTo(List.of("wikidata", "musicbrainz"));
  }
}
