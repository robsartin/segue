package com.robsartin.segue.tinker;

import com.robsartin.segue.domain.Provenance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Packs a list of {@link Provenance} into a single edge property.
 *
 * <p>This exists because property graphs have no way to attach several
 * independent records to one edge - edge properties are single-valued, and you
 * cannot point an edge at another edge. The alternatives are:
 *
 * <ol>
 *   <li>reify: turn every relationship into a Claim VERTEX with SUBJECT/OBJECT
 *       edges and one SUPPORTS edge per source. First-class provenance, but every
 *       logical hop becomes three graph hops and path queries get ugly.</li>
 *   <li>encode: this. Traversal stays clean and fast, but provenance is opaque to
 *       the query engine, so Q2 and Q4 degrade to full edge scans in Java.</li>
 * </ol>
 *
 * <p>The spike takes option 2 because paths are the payoff feature and a personal
 * graph will not outgrow a scan for years. It is worth being clear-eyed that this
 * is exactly the asymmetry the bake-off is measuring: RDF named graphs make
 * provenance queryable for free and make paths the hard part.
 */
final class ProvenanceCodec {

    private ProvenanceCodec() {
    }

    static String encode(List<Provenance> sources) {
        StringBuilder sb = new StringBuilder();
        for (Provenance p : sources) {
            if (sb.length() > 0) sb.append(Provenance.RECORD_SEP);
            sb.append(p.sourceId()).append(Provenance.FIELD_SEP)
              .append(p.sourceRef() == null ? "" : p.sourceRef()).append(Provenance.FIELD_SEP)
              .append(p.assertedAt().toEpochMilli()).append(Provenance.FIELD_SEP)
              .append(p.confidence());
        }
        return sb.toString();
    }

    static List<Provenance> decode(String encoded) {
        List<Provenance> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return out;
        for (String row : encoded.split(Provenance.RECORD_SEP, -1)) {
            if (row.isEmpty()) continue;
            String[] f = row.split(Provenance.FIELD_SEP, -1);
            if (f.length != 4) {
                throw new IllegalStateException("corrupt provenance row: " + row);
            }
            out.add(new Provenance(
                    f[0],
                    f[1].isEmpty() ? null : f[1],
                    Instant.ofEpochMilli(Long.parseLong(f[2])),
                    Double.parseDouble(f[3])));
        }
        return out;
    }

    /** Appends unless an identical claim from the same source is already present. */
    static String append(String encoded, Provenance p) {
        List<Provenance> existing = decode(encoded);
        boolean duplicate = existing.stream().anyMatch(e ->
                e.sourceId().equals(p.sourceId())
                        && java.util.Objects.equals(e.sourceRef(), p.sourceRef()));
        if (duplicate) return encoded;
        existing.add(p);
        return encode(existing);
    }
}
