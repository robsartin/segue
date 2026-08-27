package com.robsartin.segue.jena;

/**
 * IRIs for the RDF encoding.
 *
 * <p>Entities use REAL Wikidata IRIs, which is the quiet advantage of this adapter: a Wikidata dump
 * or a live SPARQL federation can be loaded straight into the same store with no identifier mapping
 * layer at all.
 *
 * <p>Predicates use a local namespace rather than Wikidata's, because the orientation is ours
 * (person DIRECTED film, not film P57 person). The mapping back to Wikidata properties lives in
 * EdgeTypes.
 */
public final class Vocab {

  public static final String WD = "http://www.wikidata.org/entity/";
  public static final String SG = "https://robsartin.com/segue/ns#";
  public static final String SGP = "https://robsartin.com/segue/prop/";
  public static final String XSD = "http://www.w3.org/2001/XMLSchema#";
  public static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";

  public static final String P_KIND = SG + "kind";
  public static final String P_INSTANCE_OF = SG + "instanceOf";
  public static final String P_SOURCE = SG + "source";
  public static final String P_SOURCE_REF = SG + "sourceRef";
  public static final String P_ASSERTED_AT = SG + "assertedAt";
  public static final String P_CONFIDENCE = SG + "confidence";
  public static final String P_VALID_FROM = SG + "validFrom";
  public static final String P_VALID_TO = SG + "validTo";

  public static final String PREFIXES =
      """
            PREFIX wd:   <http://www.wikidata.org/entity/>
            PREFIX sg:   <https://robsartin.com/segue/ns#>
            PREFIX sgp:  <https://robsartin.com/segue/prop/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX xsd:  <http://www.w3.org/2001/XMLSchema#>
            """;

  private Vocab() {}

  public static String entity(String qid) {
    return WD + qid;
  }

  public static String predicate(String typeCode) {
    return SGP + typeCode;
  }

  public static String qidOf(String entityIri) {
    return entityIri.substring(WD.length());
  }

  public static String typeCodeOf(String predicateIri) {
    return predicateIri.substring(SGP.length());
  }
}
