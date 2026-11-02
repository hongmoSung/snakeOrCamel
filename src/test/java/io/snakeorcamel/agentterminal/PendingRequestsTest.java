package io.snakeorcamel.agentterminal;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PendingRequestsTest {
    @Test void repeatedShortcutDoesNotExtendTimeoutAndDifferentRequestsKeepTheirOwnDeadlines() {
        var clock = new AtomicLong(0);
        var queue = new PendingRequests<String>(clock::get,30);
        var first = new Object(); var second = new Object();
        assertTrue(queue.offer(first,"@a","a"));
        clock.set(20);
        assertFalse(queue.offer(first,"@a","duplicate"));
        assertTrue(queue.offer(first,"@b","b"));
        assertTrue(queue.offer(second,"@a","other session"));
        clock.set(30);
        assertTrue(queue.expired(queue.entries().getFirst()));
        assertFalse(queue.expired(queue.entries().get(1)));
        assertEquals(3,queue.entries().size());
        queue.remove("a");
        assertFalse(queue.contains("a"));
        assertEquals("b",queue.entries().getFirst().value());
    }
}
