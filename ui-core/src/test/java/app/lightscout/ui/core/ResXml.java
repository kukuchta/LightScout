package app.lightscout.ui.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Reads {@code :ui-core} resource XML files for JVM tests (unit tests run with the module directory as the working
 * directory). No Android runtime is needed.
 */
final class ResXml {

    static final File RES = new File("src/main/res");

    private ResXml() {
    }

    static Document parse(String relativePath) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            return f.newDocumentBuilder().parse(new File(RES, relativePath));
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse " + relativePath, e);
        }
    }

    /** {@code <tag name="…">text</tag>} entries of a values file, in file order. */
    static Map<String, String> named(String relativePath, String tag) {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList nodes = parse(relativePath).getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            out.put(e.getAttribute("name"), e.getTextContent());
        }
        return out;
    }

    static Map<String, String> strings() {
        return named("values/strings.xml", "string");
    }

    static String string(String key) {
        String value = strings().get(key);
        if (value == null) {
            throw new AssertionError("missing string " + key);
        }
        return value;
    }

    /** Plural items {@code quantity → text} of one {@code <plurals>}. */
    static Map<String, String> plurals(String key) {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList nodes = parse("values/strings.xml").getElementsByTagName("plurals");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element p = (Element) nodes.item(i);
            if (!key.equals(p.getAttribute("name"))) {
                continue;
            }
            NodeList items = p.getElementsByTagName("item");
            for (int j = 0; j < items.getLength(); j++) {
                Element item = (Element) items.item(j);
                out.put(item.getAttribute("quantity"), item.getTextContent());
            }
        }
        return out;
    }

    /** Colour resources {@code name → ARGB} of a values folder. */
    static Map<String, Integer> colors(String folder) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : named(folder + "/colors.xml", "color").entrySet()) {
            out.put(e.getKey(), (int) Long.parseLong("FF" + e.getValue().trim().substring(1), 16));
        }
        return out;
    }

    /** Items {@code name → value} of a {@code <style>}. */
    static Map<String, String> style(String relativePath, String styleName) {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList styles = parse(relativePath).getElementsByTagName("style");
        for (int i = 0; i < styles.getLength(); i++) {
            Element s = (Element) styles.item(i);
            if (!styleName.equals(s.getAttribute("name"))) {
                continue;
            }
            out.put("@parent", s.getAttribute("parent"));
            NodeList items = s.getChildNodes();
            for (int j = 0; j < items.getLength(); j++) {
                Node n = items.item(j);
                if (n instanceof Element && "item".equals(n.getNodeName())) {
                    out.put(((Element) n).getAttribute("name"), n.getTextContent().trim());
                }
            }
            return out;
        }
        throw new AssertionError("missing style " + styleName + " in " + relativePath);
    }
}
