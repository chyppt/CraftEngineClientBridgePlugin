package cc.cebridge.carrier;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ViewerTargetsTest {
    @Test void lastViewerOwnsCleanup() {
        var targets = new ViewerTargets<String, String>();
        assertNull(targets.move("a", "crop"));
        assertNull(targets.move("b", "crop"));
        assertNull(targets.move("a", null));
        assertTrue(targets.isViewed("crop"));
        assertEquals("crop", targets.move("b", null));
        assertEquals(0, targets.targetCount());
        assertEquals(0, targets.viewerCount());
    }

    @Test void repeatedObservationDoesNotInflateReferenceCount() {
        var targets = new ViewerTargets<Integer, String>();
        for (int i = 0; i < 10000; i++) assertNull(targets.move(1, "same"));
        assertEquals("same", targets.move(1, null));
        assertFalse(targets.isViewed("same"));
    }

    @Test void changeChunkOrWorldReleasesPreviousTarget() {
        var targets = new ViewerTargets<String, String>();
        targets.move("player", "world-a:0:0:crop");
        assertEquals("world-a:0:0:crop", targets.move("player", "world-b:0:0:crop"));
        assertFalse(targets.isViewed("world-a:0:0:crop"));
        assertEquals("world-b:0:0:crop", targets.get("player"));
    }

    @Test void reacquiredTargetMustNotBeDeletedByQueuedCleanup() {
        var targets = new ViewerTargets<String, String>();
        targets.move("a", "crop");
        String queued = targets.move("a", null);
        targets.move("b", "crop");
        assertTrue(targets.isViewed(queued));
        assertEquals(1, targets.targetCount());
    }

    @Test void quitAndRepeatedReleaseAreIdempotent() {
        var targets = new ViewerTargets<String, String>();
        targets.move("p", "crop");
        assertEquals("crop", targets.move("p", null));
        assertNull(targets.move("p", null));
        assertNull(targets.get("p"));
    }

    @Test void disablingDropsAllPlayerReferences() {
        var targets = new ViewerTargets<Integer, Integer>();
        for (int i = 0; i < 1000; i++) targets.move(i, i % 50);
        targets.clear();
        assertEquals(0, targets.viewerCount());
        assertEquals(0, targets.targetCount());
        assertNull(targets.move(1, null));
    }

    @Test void randomizedLifecycleMatchesIndependentModel() {
        var targets = new ViewerTargets<Integer, Integer>();
        Map<Integer, Integer> expected = new HashMap<>();
        Random random = new Random(612);
        for (int i = 0; i < 20000; i++) {
            int player = random.nextInt(50);
            Integer next = random.nextInt(4) == 0 ? null : random.nextInt(30);
            Integer old = expected.get(player);
            if (next == null) expected.remove(player); else expected.put(player, next);
            Integer released = targets.move(player, next);
            assertEquals(old != null && !expected.containsValue(old) ? old : null, released);
            assertEquals(expected.size(), targets.viewerCount());
            assertEquals(new java.util.HashSet<>(expected.values()).size(), targets.targetCount());
        }
    }

    @Test void concurrentRegionsLeaveNoReferences() throws Exception {
        var targets = new ViewerTargets<Integer, Integer>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int player = 0; player < 100; player++) {
                int id = player;
                executor.submit(() -> {
                    for (int i = 0; i < 1000; i++) targets.move(id, i % 4);
                    targets.move(id, null);
                });
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(0, targets.viewerCount());
        assertEquals(0, targets.targetCount());
    }
}
