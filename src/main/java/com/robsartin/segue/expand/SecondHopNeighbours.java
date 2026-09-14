package com.robsartin.segue.expand;

import java.util.Objects;

/**
 * The second-hop population a run covered, and how many acts it was read beside (#319).
 *
 * @param file the file's <b>basename</b>, never its path — {@code support.KnownListInput} is the
 *     one home of that rule, and the block is meant to be pasted
 * @param isolated how many entities of that file, composed with the owner's promotions and folded
 *     onto their canonical side, the graph holds a node for and cannot place: no other member of
 *     that population is within the recommender's hop limit of them
 */
public record SecondHopNeighbours(String file, int isolated) implements Population {

  public SecondHopNeighbours {
    Objects.requireNonNull(file, "file");
    if (isolated < 0) {
      throw new IllegalArgumentException("isolated cannot be negative, got " + isolated);
    }
  }
}
