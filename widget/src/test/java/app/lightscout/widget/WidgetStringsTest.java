package app.lightscout.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

public class WidgetStringsTest {

    @Test
    public void widgetCopyMatchesTheApprovedKeysAndTemplates() throws Exception {
        Map<String, String> strings = new LinkedHashMap<>();
        NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("src/main/res/values/strings.xml")).getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            strings.put(element.getAttribute("name"), element.getTextContent());
        }

        assertEquals("mg/dL", strings.get("s12_unit"));
        assertEquals("Open LightScout to connect", strings.get("s12_first_use"));
        assertEquals("No readings", strings.get("s12_no_readings"));
        assertEquals("Glucose %1$d, %2$s, %3$s", strings.get("s12_widget_a11y"));
        assertTrue(String.format(Locale.ENGLISH, strings.get("s12_widget_a11y"),
                142, "rising", "3 minutes ago").equals("Glucose 142, rising, 3 minutes ago"));
    }
}
