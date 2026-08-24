package com.robsartin.segue.domain;

/**
 * Ontological kind - deliberately NOT domain-specific.
 *
 * <p>"Musician", "novelist", "director" are ROLES, and roles are expressed as edges (PERFORMED,
 * AUTHORED, DIRECTED), never as node types. That is what lets one Nick Cave node be all three at
 * once without a seventh enum constant.
 *
 * <p>Six kinds is intended to hold for the life of the project. If you ever feel the need to add
 * MUSICIAN or FILM here, the model is being used wrong.
 */
public enum NodeKind {
  PERSON,
  GROUP,
  WORK,
  PLACE,
  EVENT,
  CONCEPT
}
