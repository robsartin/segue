package com.robsartin.segue.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Every fixture here is invented. ADR 40 and issue #37: nothing derived from a real graph, a real
 * list or a real rating enters this repository.
 */
class GraphMlWriterTest {

  private static String render(GraphView view) throws IOException {
    StringWriter out = new StringWriter();
    new GraphMlWriter().write(view, out);
    return out.toString();
  }

  /**
   * Parses with a factory that resolves no external entities. The point here is to prove the output
   * is well-formed XML — a GraphML file no tool can open has failed at the only thing it exists for
   * — and to read attributes back rather than string-matching them.
   */
  private static Document parse(String xml)
      throws ParserConfigurationException, IOException, SAXException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setNamespaceAware(true);
    return factory
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private static GraphView oneRelationship() {
    return new GraphView(
        "a made-up view",
        List.of(
            new ViewNode("Q901", NodeKind.PERSON, "Wren Alderman"),
            new ViewNode("Q902", NodeKind.GROUP, "The Paper Kettles")),
        List.of(new ViewEdge("Q901", "Q902", "MEMBER_OF", 0.8, "invented")));
  }

  private static String dataValue(Element owner, String key) {
    NodeList data = owner.getElementsByTagName("data");
    for (int i = 0; i < data.getLength(); i++) {
      Element element = (Element) data.item(i);
      if (key.equals(element.getAttribute("key"))) {
        return element.getTextContent();
      }
    }
    return null;
  }

  @Test
  @DisplayName("the output parses as XML, which is the minimum a Gephi import needs")
  void writesWellFormedXml() throws Exception {
    Document document = parse(render(oneRelationship()));

    assertThat(document.getDocumentElement().getLocalName()).isEqualTo("graphml");
  }

  @Test
  @DisplayName("nodes carry kind and label, so a tool can colour and search on them")
  void carriesNodeAttributes() throws Exception {
    Document document = parse(render(oneRelationship()));

    Element node = (Element) document.getElementsByTagName("node").item(0);
    assertThat(node.getAttribute("id")).isEqualTo("Q901");
    assertThat(dataValue(node, "kind")).isEqualTo("PERSON");
    assertThat(dataValue(node, "label")).isEqualTo("Wren Alderman");
  }

  @Test
  @DisplayName("edges carry typeCode, confidence and sourceId — ADR 31's weak hops are filterable")
  void carriesEdgeAttributes() throws Exception {
    Document document = parse(render(oneRelationship()));

    Element edge = (Element) document.getElementsByTagName("edge").item(0);
    assertThat(edge.getAttribute("source")).isEqualTo("Q901");
    assertThat(edge.getAttribute("target")).isEqualTo("Q902");
    assertThat(dataValue(edge, "typeCode")).isEqualTo("MEMBER_OF");
    assertThat(dataValue(edge, "confidence")).isEqualTo("0.8");
    assertThat(dataValue(edge, "sourceId")).isEqualTo("invented");
  }

  @Test
  @DisplayName("every attribute used is declared as a key, or a strict reader rejects the file")
  void declaresAKeyForEveryAttribute() throws Exception {
    Document document = parse(render(oneRelationship()));

    NodeList keys = document.getElementsByTagName("key");
    assertThat(keys.getLength()).isEqualTo(6);
    for (int i = 0; i < keys.getLength(); i++) {
      Element key = (Element) keys.item(i);
      assertThat(key.getAttribute("attr.name")).isEqualTo(key.getAttribute("id"));
      assertThat(key.getAttribute("for")).isIn("node", "edge");
    }
  }

  @Test
  @DisplayName(
      "edges are directed and each has its own id, so a multigraph survives the round trip")
  void writesADirectedMultigraph() throws Exception {
    GraphView view =
        new GraphView(
            "two relationships between one pair",
            List.of(
                new ViewNode("Q901", NodeKind.PERSON, "Wren Alderman"),
                new ViewNode("Q902", NodeKind.GROUP, "The Paper Kettles")),
            List.of(
                new ViewEdge("Q901", "Q902", "MEMBER_OF", 1.0, "invented"),
                new ViewEdge("Q901", "Q902", "PERFORMED_WITH", 0.8, "invented")));
    Document document = parse(render(view));

    Element graph = (Element) document.getElementsByTagName("graph").item(0);
    assertThat(graph.getAttribute("edgedefault")).isEqualTo("directed");
    Element first = (Element) document.getElementsByTagName("edge").item(0);
    Element second = (Element) document.getElementsByTagName("edge").item(1);
    assertThat(first.getAttribute("id")).isNotEmpty().isNotEqualTo(second.getAttribute("id"));
  }

  @Test
  @DisplayName("markup in a label is escaped, so one ampersand cannot break the file")
  void escapesMarkup() throws Exception {
    GraphView view =
        new GraphView(
            "a made-up view",
            List.of(new ViewNode("Q901", NodeKind.GROUP, "Salt & <Pepper> \"Trio\"")),
            List.of());
    Document document = parse(render(view));

    Element node = (Element) document.getElementsByTagName("node").item(0);
    assertThat(dataValue(node, "label")).isEqualTo("Salt & <Pepper> \"Trio\"");
  }

  @Test
  @DisplayName("the classes a claim recorded travel as an attribute, for Gephi to filter on")
  void writesInstanceOfAsItsOwnAttribute() throws Exception {
    GraphView view =
        new GraphView(
            "a made-up view",
            List.of(
                new ViewNode(
                    "Q901", NodeKind.WORK, "Hollow Tide", List.of("Q482994", "Q105543609"))),
            List.of());
    Document document = parse(render(view));

    assertThat(dataValue((Element) document.getElementsByTagName("node").item(0), "instanceOf"))
        .isEqualTo("Q482994 Q105543609");
  }

  @Test
  @DisplayName("a node whose source stated no classes carries no instanceOf at all")
  void omitsInstanceOfWhenNothingWasStated() throws Exception {
    Document document = parse(render(oneRelationship()));

    assertThat(dataValue((Element) document.getElementsByTagName("node").item(0), "instanceOf"))
        .isNull();
  }

  @Test
  @DisplayName(
      "no tooltip here: Gephi shows attributes natively, so presentation stays the reader's")
  void writesNoPresentation() throws Exception {
    GraphView view =
        new GraphView(
            "a made-up view",
            List.of(new ViewNode("Q901", NodeKind.WORK, "Hollow Tide", List.of("Q482994"))),
            List.of());

    assertThat(render(view)).doesNotContain("tooltip").doesNotContain("fillcolor");
  }

  @Test
  @DisplayName("no affinity key is declared when no node carries a rating")
  void omitsTheAffinityKeyByDefault() throws Exception {
    String xml = render(oneRelationship());

    assertThat(xml).doesNotContain("affinity");
  }

  @Test
  @DisplayName("a rating becomes its own typed attribute when the operator asked for it")
  void writesAffinityAsItsOwnAttribute() throws Exception {
    GraphView view =
        new GraphView(
            "a made-up view",
            List.of(
                new ViewNode("Q901", NodeKind.PERSON, "Wren Alderman").withAffinity(4),
                new ViewNode("Q902", NodeKind.GROUP, "The Paper Kettles")),
            List.of());
    Document document = parse(render(view));

    assertThat(dataValue((Element) document.getElementsByTagName("node").item(0), "affinity"))
        .isEqualTo("4");
    assertThat(dataValue((Element) document.getElementsByTagName("node").item(1), "affinity"))
        .isNull();
  }

  @Test
  @DisplayName("an empty view is a valid, empty GraphML document")
  void writesAnEmptyGraph() throws Exception {
    Document document = parse(render(new GraphView("nothing at all", List.of(), List.of())));

    assertThat(document.getElementsByTagName("node").getLength()).isZero();
    assertThat(document.getElementsByTagName("edge").getLength()).isZero();
  }

  @Test
  @DisplayName("the extension names the format")
  void namesItsExtension() {
    assertThat(new GraphMlWriter().extension()).isEqualTo("graphml");
  }
}
