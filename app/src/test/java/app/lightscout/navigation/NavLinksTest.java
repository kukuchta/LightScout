package app.lightscout.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.ReadinessMode;
import java.io.File;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * BUG-05 / ruling Q-94: S-06 and S-07 are reached from a sibling graph through internal deep links. The URIs built
 * by {@link NavLinks} must match the {@code <deepLink>} declarations of {@code onboarding_graph.xml}, the
 * {@code {mode}} placeholder must be the Safe Args argument, and no other destination may carry a deep link.
 */
public class NavLinksTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    @Test
    public void connectionUri_carriesTheModeName() {
        assertEquals("lightscout-internal://connection/EDIT",
                NavLinks.uri(NavRequest.connection(ConnectionMode.EDIT)));
        assertEquals("lightscout-internal://connection/ONBOARDING",
                NavLinks.uri(NavRequest.connection(ConnectionMode.ONBOARDING)));
    }

    @Test
    public void readinessUri_carriesTheModeName() {
        assertEquals("lightscout-internal://readiness/CHECK", NavLinks.uri(NavRequest.readiness(ReadinessMode.CHECK)));
        assertEquals("lightscout-internal://readiness/ONBOARDING",
                NavLinks.uri(NavRequest.readiness(ReadinessMode.ONBOARDING)));
    }

    @Test
    public void onlyS06AndS07_areDeepLinkSteps_graphsGoById() {
        assertTrue(NavLinks.isDeepLink(NavRequest.entry("id")));
        assertTrue(NavLinks.isDeepLink(NavRequest.connection(ConnectionMode.EDIT)));
        assertTrue(NavLinks.isDeepLink(NavRequest.readiness(ReadinessMode.CHECK)));
        NavRequest home = NavRequest.replacing(NavTarget.HOME, NavTarget.ONBOARDING);
        assertFalse(NavLinks.isDeepLink(home));
        assertThrows(IllegalArgumentException.class, () -> NavLinks.uri(home));
        assertThrows(NullPointerException.class, () -> NavLinks.uri(null));
    }

    @Test
    public void entryGraphDeclaresTheModalInternalRouteAndExclusiveNullableInputs() throws Exception {
        Document graph = xml(new File(root(), "feature-entry/src/main/res/navigation/entry_graph.xml"));
        NodeList links = graph.getElementsByTagName("deepLink");
        assertEquals(1, links.getLength());
        Element link = (Element) links.item(0);
        Element destination = (Element) link.getParentNode();
        assertEquals("dialog", destination.getTagName());
        assertEquals(NavLinks.ENTRY_PATTERN, link.getAttributeNS(APP, "uri"));
        for (String name : new String[] {"mode", "entryId", "initialType"}) {
            assertTrue(hasArgument(destination, name));
        }
    }

    @Test
    public void everyModeOfEveryLink_isAValidEnumName() {
        for (ConnectionMode m : ConnectionMode.values()) {
            String uri = NavLinks.uri(NavRequest.connection(m));
            assertEquals(m, ConnectionMode.valueOf(uri.substring(uri.lastIndexOf('/') + 1)));
        }
        for (ReadinessMode m : ReadinessMode.values()) {
            String uri = NavLinks.uri(NavRequest.readiness(m));
            assertEquals(m, ReadinessMode.valueOf(uri.substring(uri.lastIndexOf('/') + 1)));
        }
    }

    @Test
    public void onboardingGraph_declaresExactlyTheTwoInternalLinks_onS06AndS07_withTheModeArgument()
            throws Exception {
        Document graph = xml(new File(root(), "feature-onboarding/src/main/res/navigation/onboarding_graph.xml"));
        Map<String, String> links = new TreeMap<>();
        NodeList deepLinks = graph.getElementsByTagName("deepLink");
        for (int i = 0; i < deepLinks.getLength(); i++) {
            Element link = (Element) deepLinks.item(i);
            Element destination = (Element) link.getParentNode();
            assertEquals("fragment", destination.getTagName());
            links.put(destination.getAttributeNS(ANDROID, "id"), link.getAttributeNS(APP, "uri"));
            assertTrue("mode is an argument of " + destination.getAttributeNS(ANDROID, "id"),
                    hasArgument(destination, "mode"));
        }
        assertEquals(Map.of("@+id/connectionFragment", NavLinks.CONNECTION_PATTERN,
                "@+id/readinessFragment", NavLinks.READINESS_PATTERN), links);
    }

    @Test
    public void noOtherGraph_carriesADeepLink() throws Exception {
        for (String path : new String[] {"app/src/main/res/navigation/root_graph.xml",
                "feature-home/src/main/res/navigation/home_graph.xml"}) {
            Document graph = xml(new File(root(), path));
            assertEquals(path, 0, graph.getElementsByTagName("deepLink").getLength());
        }
    }

    /**
     * BUG-08 review (Q-95): MainActivity's clean intent may now carry ACTION_MAIN / CATEGORY_LAUNCHER. No deep link
     * may match on an action or a MIME type, so an intent action can never select a destination.
     */
    @Test
    public void noDeepLink_inAnyGraph_hasAnActionOrAMimeType() throws Exception {
        for (String path : new String[] {"app/src/main/res/navigation/root_graph.xml",
                "feature-home/src/main/res/navigation/home_graph.xml",
                "feature-onboarding/src/main/res/navigation/onboarding_graph.xml",
                "feature-entry/src/main/res/navigation/entry_graph.xml"}) {
            NodeList deepLinks = xml(new File(root(), path)).getElementsByTagName("deepLink");
            for (int i = 0; i < deepLinks.getLength(); i++) {
                Element link = (Element) deepLinks.item(i);
                for (String attribute : new String[] {"action", "mimeType"}) {
                    assertFalse(path + ": <deepLink> has app:" + attribute,
                            link.hasAttributeNS(APP, attribute) || link.hasAttribute(attribute)
                                    || link.hasAttributeNS(ANDROID, attribute));
                }
            }
        }
    }

    private static boolean hasArgument(Element destination, String name) {
        NodeList args = destination.getElementsByTagName("argument");
        for (int i = 0; i < args.getLength(); i++) {
            Element arg = (Element) args.item(i);
            if (arg.getParentNode() == destination && name.equals(arg.getAttributeNS(ANDROID, "name"))) {
                return true;
            }
        }
        return false;
    }

    private static File root() {
        String dir = System.getProperty("lightscout.rootDir");
        assertTrue("lightscout.rootDir is set by app/build.gradle.kts", dir != null);
        return new File(dir);
    }

    private static Document xml(File file) throws Exception {
        assertTrue(file + " exists", file.exists());
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(file);
    }
}
