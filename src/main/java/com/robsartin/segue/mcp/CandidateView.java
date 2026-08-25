package com.robsartin.segue.mcp;

import com.robsartin.segue.domain.NodeKind;
import org.jspecify.annotations.Nullable;

/**
 * The wire shape of {@link com.robsartin.segue.domain.Candidate}.
 *
 * <p>{@code description} is genuinely optional — Wikidata does not supply one for every hit — so
 * unlike the domain record (which cannot carry a third-party nullability annotation, ADR 18) this
 * type says so where a schema-generating client can see it: Spring AI's {@code
 * AbstractSpringAiSchemaModule.checkRequired} (package {@code
 * org.springframework.ai.util.json.schema}) marks every record component required unless it carries
 * a JSpecify {@code @Nullable}, and a real search result with no description would otherwise
 * violate its own declared schema.
 */
public record CandidateView(
    String qid, String label, @Nullable String description, NodeKind kind) {}
