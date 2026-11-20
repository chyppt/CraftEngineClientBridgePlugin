package cc.cebridge.carrier;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecentBlockChecksTest {
    @Test void observersOfTheSameBlockShareTheCompletedWork() {
        var checks = new RecentBlockChecks<String, Object>(16, 100);
        Object state = new Object();
        int rebuilds = 0;
        for (int viewer = 0; viewer < 1000; viewer++) {
            if (!checks.isFresh("world:1:2:3", state, 1)) {
                rebuilds++; checks.record("world:1:2:3", state, 1);
            }
        }
        assertEquals(1, rebuilds);
    }
    @Test void cropStateChangesBypassTheTtl() {
        var checks = new RecentBlockChecks<String, Object>(16, 100);
        checks.record("crop", new Object(), 0);
        assertFalse(checks.isFresh("crop", new Object(), 1));
    }
    @Test void expiryForcesARecheckEvenWhenTheStateDidNotChange() {
        var checks = new RecentBlockChecks<String, Object>(16, 100);
        Object state = new Object(); checks.record("crop", state, 0);
        assertTrue(checks.isFresh("crop", state, 99));
        assertFalse(checks.isFresh("crop", state, 100));
    }
    @Test void leastRecentlyUsedEntryIsEvictedAtCapacity() {
        var checks = new RecentBlockChecks<String, Object>(2, 100);
        Object state = new Object();
        checks.record("a", state, 0); checks.record("b", state, 0);
        assertTrue(checks.isFresh("a", state, 1)); checks.record("c", state, 1);
        assertFalse(checks.isFresh("b", state, 2)); assertTrue(checks.isFresh("a", state, 2));
    }
    @Test void reloadReleasesAllStateReferences() {
        var checks = new RecentBlockChecks<String, Object>(2, 100);
        Object state = new Object(); checks.record("a", state, 0); checks.clear();
        assertFalse(checks.isFresh("a", state, 1));
    }
    @Test void invalidBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RecentBlockChecks<>(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RecentBlockChecks<>(1, 0));
    }
    @Test void departureEvictsOnlyTheReleasedTarget() {
        var checks = new RecentBlockChecks<String, Object>(16, 100);
        Object state = new Object();
        checks.record("left", state, 0); checks.record("still-viewed", state, 0);
        checks.remove("left");
        assertFalse(checks.isFresh("left", state, 1));
        assertTrue(checks.isFresh("still-viewed", state, 1));
    }
}
