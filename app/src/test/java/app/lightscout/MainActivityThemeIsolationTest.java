package app.lightscout;

import static org.junit.Assert.assertEquals;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * R-95 theme isolation on the source manifests: the reviewed Glass Mono child theme is assigned to MainActivity only;
 * the application theme and the alarm component keep {@code Theme.LightScout}.
 */
public class MainActivityThemeIsolationTest {

    private static final String A = "http://schemas.android.com/apk/res/android";

    private static Document parse(String path) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new File(path));
    }

    private static String theme(Document manifest, String tag, String name) {
        NodeList list = manifest.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            if (name == null || name.equals(e.getAttributeNS(A, "name"))) {
                return e.getAttributeNS(A, "theme");
            }
        }
        throw new AssertionError("missing " + tag + " " + name);
    }

    @Test
    public void glassThemeIsMainActivityOnly() throws Exception {
        Document app = parse("src/main/AndroidManifest.xml");
        assertEquals("@style/Theme.LightScout", theme(app, "application", null));
        assertEquals("@style/Theme.LightScout.Glass", theme(app, "activity", ".MainActivity"));
        assertEquals("", theme(app, "activity", "app.lightscout.alarm.AlarmActivity"));
        Document alarm = parse("../alarm/src/main/AndroidManifest.xml");
        assertEquals("@style/Theme.LightScout", theme(alarm, "activity", "app.lightscout.alarm.AlarmActivity"));
    }
}
