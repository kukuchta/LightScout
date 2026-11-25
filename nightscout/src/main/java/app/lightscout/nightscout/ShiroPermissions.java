package app.lightscout.nightscout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;

/**
 * Nightscout permission matching (BUG-15, D-060): decides which IF-32 letters a subject holds from the IF-30
 * {@code permissionGroups}, with the semantics of the {@code shiro-trie} npm package that Nightscout uses
 * ({@code lib/api3/security.js} {@code checkPermission}: a permission is granted when <em>any</em> group's trie
 * grants it).
 *
 * <p>Semantics, per granted pattern. They match {@code entrecode/shiro-trie} {@code index.js} ({@code _add} /
 * {@code _check}) for every grant of up to three parts, and for longer grants whose extra parts are all {@code *}:
 * <ul>
 *   <li>a permission is a list of {@code :}-separated parts;</li>
 *   <li>a granted part is {@code *} (any value) or a {@code ,}-separated list of alternatives (one of which may
 *       itself be {@code *});</li>
 *   <li>a granted pattern with fewer parts than the request implies {@code *} for the missing trailing parts
 *       ({@code api} grants {@code api:treatments:create}; {@code *} grants everything); a granted pattern with
 *       more parts matches only if every extra part is exactly {@code *};</li>
 *   <li>matching is exact and case-sensitive; nothing is trimmed; an empty part matches nothing real;</li>
 *   <li>the groups are a union; an empty group grants nothing.</li>
 * </ul>
 *
 * <p><b>Deliberately conservative for grants with four or more parts.</b> shiro-trie's {@code _check} appends
 * {@code *} to the request and then accepts any {@code *} key in the trie at that depth, so it also grants
 * {@code api:treatments:create} for {@code api:treatments:create:*:own} and for
 * {@code api:treatments:create:own,*}. This class denies both: a grant narrowed by further parts is not taken as
 * covering the whole operation. Nightscout's built-in roles use at most three parts; for custom roles the
 * difference can only be a false „missing permission”, never a false grant (BUG-15 review round 1).
 *
 * <p>Pure and immutable. Never log the permission strings (SR-07).
 */
final class ShiroPermissions {

    /** IF-32 letters in their canonical order, with the Nightscout API operation each one stands for. */
    private static final char[] LETTERS = {'c', 'r', 'u', 'd'};
    private static final String[] OPERATIONS = {"create", "read", "update", "delete"};

    private final List<String[]> patterns;

    private ShiroPermissions(List<String[]> patterns) {
        this.patterns = patterns;
    }

    /**
     * Builds the matcher from the IF-30 {@code permissionGroups}.
     *
     * @param groups one list of permission strings per role; not null, no null elements
     * @return the matcher
     */
    static ShiroPermissions of(List<List<String>> groups) {
        List<String[]> out = new ArrayList<>();
        for (List<String> group : groups) {
            for (String permission : group) {
                out.add(permission.split(":", -1));
            }
        }
        return new ShiroPermissions(Collections.unmodifiableList(out));
    }

    /**
     * Whether any group grants the permission.
     *
     * @param permission a concrete permission without wildcards or alternatives, e.g. {@code api:entries:read}
     * @return {@code true} if granted
     */
    boolean check(String permission) {
        String[] requested = permission.split(":", -1);
        for (String[] granted : patterns) {
            if (matches(granted, requested)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The IF-32 letters per collection: {@code c}/{@code r}/{@code u}/{@code d} for
     * {@code api:<collection>:create|read|update|delete}. Collections without any letter are absent.
     *
     * @return the permissions
     */
    Permissions toPermissions() {
        Map<NsCollection, String> letters = new EnumMap<>(NsCollection.class);
        for (NsCollection c : NsCollection.values()) {
            StringBuilder sb = new StringBuilder(LETTERS.length);
            String name = OkHttpNightscoutGateway.collectionName(c);
            for (int i = 0; i < LETTERS.length; i++) {
                if (check("api:" + name + ":" + OPERATIONS[i])) {
                    sb.append(LETTERS[i]);
                }
            }
            if (sb.length() > 0) {
                letters.put(c, sb.toString());
            }
        }
        return new Permissions(letters);
    }

    private static boolean matches(String[] granted, String[] requested) {
        for (int i = 0; i < requested.length; i++) {
            if (i >= granted.length) {
                return true;                               // implied trailing '*'
            }
            if (!partMatches(granted[i], requested[i])) {
                return false;
            }
        }
        for (int i = requested.length; i < granted.length; i++) {
            if (!"*".equals(granted[i])) {
                return false;                              // a more specific grant does not cover the request
            }
        }
        return true;
    }

    private static boolean partMatches(String granted, String requested) {
        for (String alternative : granted.split(",", -1)) {
            if ("*".equals(alternative) || alternative.equals(requested)) {
                return true;
            }
        }
        return false;
    }
}
