package app.lightscout.navigation;

import java.util.Objects;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.UnsupportedEncodingException;

/**
 * App-internal deep-link URIs of S-06 / S-07 (BUG-05, ruling Q-94). Navigation resolves a destination <b>id</b> only
 * among the children of the current graph and its parents, so S-01 ({@code home_graph}, later S-04) cannot reach
 * {@code connectionFragment} / {@code readinessFragment} inside the sibling {@code onboarding_graph} by id. Both
 * destinations therefore declare an internal {@code <deepLink>} in {@code onboarding_graph.xml} and
 * {@link NavControllerPort} navigates with a {@code NavDeepLinkRequest} built from {@link #uri(NavRequest)}.
 *
 * <p>The URIs are never an external surface: no manifest {@code <nav-graph>}, no intent filter (TST-13 audit), and
 * {@code MainActivity} hands the NavController only a clean intent (SR-21). Android-free (JVM-tested against the
 * graph XML).
 */
public final class NavLinks {

    /** Scheme of every internal link; never registered in a manifest. */
    public static final String SCHEME = "lightscout-internal";

    /** {@code onboarding_graph.xml} deep link of S-06 ({@code {mode}} = {@code ConnectionMode} name). */
    public static final String CONNECTION_PATTERN = SCHEME + "://connection/{mode}";

    /** {@code onboarding_graph.xml} deep link of S-07 ({@code {mode}} = {@code ReadinessMode} name). */
    public static final String READINESS_PATTERN = SCHEME + "://readiness/{mode}";
    public static final String ENTRY_PATTERN = SCHEME + "://entry/{mode}?entryId={entryId}&initialType={initialType}";

    private NavLinks() {
    }

    /**
     * Tells whether a step is executed through an internal deep link (a destination inside a sibling graph).
     *
     * @param request the step
     * @return {@code true} for S-06 and S-07
     */
    public static boolean isDeepLink(NavRequest request) {
        NavTarget target = Objects.requireNonNull(request, "request").target();
        return target == NavTarget.CONNECTION || target == NavTarget.READINESS || target == NavTarget.ENTRY;
    }

    /**
     * Returns the internal URI of a deep-link step: the graph pattern with {@code {mode}} replaced by the enum
     * constant name (the Safe Args argument type parses it back by name).
     *
     * @param request an S-06 or S-07 step
     * @return the URI string
     * @throws IllegalArgumentException if the step is not a deep-link step
     */
    public static String uri(NavRequest request) {
        Objects.requireNonNull(request, "request");
        switch (request.target()) {
            case ENTRY:
                return SCHEME + "://entry/" + (request.entryId() == null
                        ? "CREATE?initialType=" + request.initialType().name()
                        : "EDIT?entryId=" + encodeIdentifier(request.entryId()));
            case CONNECTION:
                return CONNECTION_PATTERN.replace("{mode}", request.connectionMode().name());
            case READINESS:
                return READINESS_PATTERN.replace("{mode}", request.readinessMode().name());
            default:
                throw new IllegalArgumentException("no internal deep link for " + request.target());
        }
    }

    private static String encodeIdentifier(String identifier) {
        try {
            return URLEncoder.encode(identifier, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
