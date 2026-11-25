package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;

/**
 * BUG-15 / D-060: {@link ShiroPermissions} follows the {@code shiro-trie} semantics Nightscout uses for
 * {@code permissionGroups} (wildcards, {@code ,} alternatives, implied trailing parts, union of groups).
 */
public class ShiroPermissionsTest {

    @SafeVarargs
    private static ShiroPermissions shiro(List<String>... groups) {
        return ShiroPermissions.of(List.of(groups));
    }

    @SafeVarargs
    private static Permissions letters(List<String>... groups) {
        return shiro(groups).toPermissions();
    }

    @Test
    public void wildcardParts() {
        ShiroPermissions s = shiro(List.of("*:*:read"));
        assertTrue(s.check("api:entries:read"));
        assertTrue(s.check("api:treatments:read"));
        assertFalse(s.check("api:treatments:create"));
        ShiroPermissions mid = shiro(List.of("api:*:create"));
        assertTrue(mid.check("api:treatments:create"));
        assertFalse(mid.check("api:treatments:read"));
        assertFalse(mid.check("other:treatments:create"));
    }

    @Test
    public void alternatives() {
        ShiroPermissions s = shiro(List.of("api:treatments,entries:create,update"));
        assertTrue(s.check("api:treatments:create"));
        assertTrue(s.check("api:entries:update"));
        assertFalse(s.check("api:treatments:delete"));
        assertFalse(s.check("api:profile:create"));
        assertTrue(shiro(List.of("api:x,*:read")).check("api:profile:read"));
    }

    @Test
    public void impliedTrailingParts() {
        assertTrue(shiro(List.of("api")).check("api:treatments:create"));
        assertTrue(shiro(List.of("api:treatments")).check("api:treatments:delete"));
        assertFalse(shiro(List.of("api:treatments")).check("api:entries:read"));
        assertTrue(shiro(List.of("api:treatments:create:*")).check("api:treatments:create"));
        assertFalse(shiro(List.of("api:treatments:create:own")).check("api:treatments:create"));
    }

    @Test
    public void adminStar() {
        Permissions p = letters(List.of("*"));
        for (NsCollection c : NsCollection.values()) {
            assertEquals("crud", p.letters().get(c));
        }
        assertEquals("crud", letters(List.of("api:*")).letters().get(NsCollection.PROFILE));
    }

    @Test
    public void caseSensitiveNoTrim() {
        assertFalse(shiro(List.of("API:treatments:create")).check("api:treatments:create"));
        assertFalse(shiro(List.of("api:Treatments:create")).check("api:treatments:create"));
        assertFalse(shiro(List.of("api:treatments: create")).check("api:treatments:create"));
        assertFalse(shiro(List.of("")).check("api:treatments:create"));
        assertFalse(shiro(List.of("api::create")).check("api:treatments:create"));
    }

    /** Parts match whole values only: no prefixes, no empty trailing part as a wildcard. */
    @Test
    public void wholePartBoundaries() {
        assertFalse(shiro(List.of("api:treatments:")).check("api:treatments:create"));
        assertFalse(shiro(List.of("api:treat:create")).check("api:treatments:create"));
        assertFalse(shiro(List.of("api:treatments:cr")).check("api:treatments:create"));
        assertFalse(shiro(List.of("ap")).check("api:entries:read"));
    }

    /**
     * Grants with four or more parts: deliberately stricter than shiro-trie, which grants
     * {@code api:treatments:create} for both of these (see the {@link ShiroPermissions} Javadoc).
     */
    @Test
    public void longerGrantsAreConservative() {
        assertFalse(shiro(List.of("api:treatments:create:*:own")).check("api:treatments:create"));
        assertFalse(shiro(List.of("api:treatments:create:own,*")).check("api:treatments:create"));
        assertNull(letters(List.of("api:treatments:create:*:own", "api:treatments:update:own,*"))
                .letters().get(NsCollection.TREATMENTS));
        assertTrue(shiro(List.of("api:treatments:create:*:*")).check("api:treatments:create"));
    }

    /** EV-11 production evidence: readable + custom treatments CUD role + empty default role. */
    @Test
    public void realProductionGroups() {
        Permissions p = letters(List.of("*:*:read"),
                List.of("api:treatments:create", "api:treatments:update", "api:treatments:delete"), List.of());
        assertEquals("crud", p.letters().get(NsCollection.TREATMENTS));
        assertEquals("r", p.letters().get(NsCollection.ENTRIES));
        assertEquals("r", p.letters().get(NsCollection.DEVICESTATUS));
        assertEquals("r", p.letters().get(NsCollection.PROFILE));
    }

    /** Built-in careportal = api:treatments:create only → c without u/d (write check fails). */
    @Test
    public void careportal() {
        Permissions only = letters(List.of("api:treatments:create"));
        assertEquals("c", only.letters().get(NsCollection.TREATMENTS));
        assertNull(only.letters().get(NsCollection.ENTRIES));
        Permissions withRead = letters(List.of("*:*:read"), List.of("api:treatments:create"));
        assertEquals("cr", withRead.letters().get(NsCollection.TREATMENTS));
        assertFalse(withRead.has(NsCollection.TREATMENTS, 'u'));
        assertFalse(withRead.has(NsCollection.TREATMENTS, 'd'));
    }

    @Test
    public void readableOnly() {
        Permissions p = letters(List.of("*:*:read"), List.of());
        assertEquals(4, p.letters().size());
        for (NsCollection c : NsCollection.values()) {
            assertEquals("r", p.letters().get(c));
        }
    }

    @Test
    public void emptyGroupsGrantNothing() {
        assertTrue(letters().letters().isEmpty());
        assertTrue(letters(List.of(), List.of()).letters().isEmpty());
        assertFalse(shiro(List.of()).check("api:entries:read"));
    }

    /** Grants for collections the app does not use do not leak into the known ones. */
    @Test
    public void unknownCollections() {
        Permissions p = letters(List.of("api:food:create,read,update,delete", "api:settings"));
        assertTrue(p.letters().isEmpty());
        assertTrue(shiro(List.of("api:food")).check("api:food:read"));
    }

    @Test
    public void unionAcrossGroups() {
        Permissions p = letters(List.of("api:treatments:create"), List.of("api:treatments:update"),
                List.of("api:treatments:delete"), List.of("api:treatments:read", "api:entries:read"));
        assertEquals("crud", p.letters().get(NsCollection.TREATMENTS));
        assertEquals("r", p.letters().get(NsCollection.ENTRIES));
    }
}
