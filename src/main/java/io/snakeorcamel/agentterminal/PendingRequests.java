package io.snakeorcamel.agentterminal;

import java.util.*;
import java.util.function.LongSupplier;

/** EDT-owned bounded-lifetime queue. Repeated offers do not extend an existing deadline. */
final class PendingRequests<T> {
    record Entry<T>(Object target, String reference, T value, long deadline) {}
    private final List<Entry<T>> entries = new ArrayList<>();
    private final LongSupplier clock;
    private final long timeout;
    PendingRequests(LongSupplier clock, long timeout) { this.clock = clock; this.timeout = timeout; }
    boolean offer(Object target, String reference, T value) {
        if (entries.stream().anyMatch(e -> e.target == target && e.reference.equals(reference))) return false;
        entries.add(new Entry<>(target,reference,value,clock.getAsLong()+timeout)); return true;
    }
    List<Entry<T>> entries() { return List.copyOf(entries); }
    boolean contains(T value) { return entries.stream().anyMatch(e -> e.value == value); }
    boolean expired(Entry<T> entry) { return clock.getAsLong() >= entry.deadline; }
    void remove(T value) { entries.removeIf(e -> e.value == value); }
    boolean isEmpty() { return entries.isEmpty(); }
}
