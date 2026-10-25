package cn.xianhua.pocketkit.reader;

import java.io.IOException;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded XML without DTDs or external entities, including on Android's XML factory. */
public final class SafeXml {
    private SafeXml() { }

    public static Document parse(byte[] xml) throws IOException {
        if (xml.length > ReaderIo.MAX_ARCHIVE_ENTRY_BYTES) throw new IOException("XML 条目过大");
        String source = ReaderText.decode(xml).text();
        if (source.indexOf('\0') >= 0 || source.contains("<!DOCTYPE") || source.contains("<!ENTITY"))
            throw new IOException("XML 不允许 DTD、实体声明或未识别的编码");
        try {
            // Both passes consume this exact decoded text, not a second, differently decoded byte stream.
            SAXParserFactory sax = SAXParserFactory.newInstance();
            sax.setNamespaceAware(true);
            XMLReader scan = sax.newSAXParser().getXMLReader();
            scan.setEntityResolver((publicId, systemId) -> { throw new SAXException("外部实体被禁用"); });
            DefaultHandler guard = new DefaultHandler() {
                private int depth, nodes;
                @Override public void startElement(String uri, String local, String name, org.xml.sax.Attributes attrs) throws SAXException {
                    if (++depth > 128 || ++nodes > 100_000) throw new SAXException("XML 层级或节点过多");
                }
                @Override public void endElement(String uri, String local, String name) { depth--; }
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
            };
            scan.setContentHandler(guard); scan.setErrorHandler(guard);
            scan.parse(new InputSource(new StringReader(source)));
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            try { factory.setXIncludeAware(false); } catch (UnsupportedOperationException unsupported) { /* Default is false. */ }
            factory.setExpandEntityReferences(false);
            optionalFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            optionalFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            optionalFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            optionalFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            try { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", ""); factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", ""); }
            catch (IllegalArgumentException unsupported) { /* Android: the preflight and rejecting resolver remain mandatory. */ }
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("外部实体被禁用"); });
            builder.setErrorHandler(guard);
            return builder.parse(new InputSource(new StringReader(source)));
        } catch (SAXException ex) {
            throw new IOException("XML 不安全或格式损坏：" + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new IOException("无法安全读取 XML：" + ex.getMessage(), ex);
        }
    }

    private static void optionalFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try { factory.setFeature(name, value); }
        catch (javax.xml.parsers.ParserConfigurationException | UnsupportedOperationException unsupported) { }
    }

    public static String local(Node node) {
        return node.getLocalName() == null ? node.getNodeName().replaceFirst("^.*:", "") : node.getLocalName();
    }
    public static String attribute(Element element, String localName) {
        if (element.hasAttribute(localName)) return element.getAttribute(localName);
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attr = element.getAttributes().item(i);
            if (local(attr).equals(localName)) return attr.getNodeValue();
        }
        return "";
    }
    public static String attributeNS(Element element, String namespace, String localName) {
        String value = element.getAttributeNS(namespace, localName);
        return value == null ? "" : value;
    }
    public static java.util.List<Element> descendants(Node root, String localName) throws IOException {
        java.util.ArrayList<Element> result = new java.util.ArrayList<>();
        walk(root, localName, result, new int[]{0});
        return result;
    }
    private static void walk(Node node, String wanted, java.util.List<Element> result, int[] visited) throws IOException {
        if (++visited[0] > 200_000) throw new IOException("XML 节点过多");
        if (node instanceof Element element && local(element).equals(wanted)) result.add(element);
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) walk(children.item(i), wanted, result, visited);
    }
}
