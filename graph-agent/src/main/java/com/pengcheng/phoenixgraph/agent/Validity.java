package com.pengcheng.phoenixgraph.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The window in which a fact is true: a vertex or an edge is <em>valid</em> at an instant when
 * {@code from <= instant < to}.
 *
 * <p>This is the third time an element carries, next to {@code createdTime} (when the row was written) and
 * {@code lastUpdatedTime} (when it was last rewritten): those say what the database did, this says what the
 * <em>world</em> did. A deployment relation is valid from the release onward; a topology relation is valid
 * until the service is retired; the state of the cache before a change and after it are two different facts,
 * not two versions of one row. Without it a graph answers "what is true now" by accident — it answers
 * "what has ever been written", which is why a September incident keeps explaining an October fault.
 *
 * <p>Both ends are optional: {@code null} means "since the beginning of time" and "for ever" respectively, so
 * an unversioned element is valid at every instant. The convention is a pair of properties named
 * {@value #FROM_PROPERTY} and {@value #TO_PROPERTY} (LONG, epoch millis), which is what the ingestion writes
 * and what the queries filter on.
 */
public record Validity(Long from, Long to) {

    /** The property a versioned vertex or edge declares for the start of its window. */
    public static final String FROM_PROPERTY = "validFrom";

    /** The property a versioned vertex or edge declares for the end of its window (exclusive). */
    public static final String TO_PROPERTY = "validTo";

    /** Valid at every instant. */
    public static final Validity ALWAYS = new Validity(null, null);

    public Validity {
        if (from != null && to != null && to <= from) {
            throw new IllegalArgumentException("validTo (" + to + ") must be after validFrom (" + from + ")");
        }
    }

    /** Valid at every instant, for an element that carries no window. */
    public static Validity always() {
        return ALWAYS;
    }

    /** Whether this window is unbounded on both ends. */
    public boolean isAlways() {
        return from == null && to == null;
    }

    /** Whether the fact holds at {@code instant} (epoch millis). */
    public boolean validAt(long instant) {
        return (from == null || instant >= from) && (to == null || instant < to);
    }

    /**
     * The window of two facts about the same thing: the earliest start and the last end, open-ended winning.
     * An entity read in two documents was there for both, so its window is their union.
     */
    public Validity union(Validity other) {
        if (other == null) {
            return this;
        }
        Long start = from == null || other.from == null ? null : Math.min(from, other.from);
        Long end = to == null || other.to == null ? null : Math.max(to, other.to);
        return new Validity(start, end);
    }

    /**
     * Parses a value a client wrote: epoch millis, an ISO-8601 instant ({@code 2026-10-24T10:00:00Z}), an
     * offset date-time, a local date-time (UTC) or a plain date ({@code 2026-10-24}, midnight UTC). Blank or
     * absent means "unbounded", so a request that names only one end works.
     */
    public static Long parseMillis(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(text);
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (RuntimeException ignored) {
            // not an instant: try the other shapes below
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // not an offset date-time
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException ignored) {
            // not a local date-time
        }
        try {
            return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Not a date or an epoch in millis: '" + text
                    + "' (use 2026-10-24, 2026-10-24T10:00:00Z or the millis themselves)");
        }
    }

    /** A window from two client-written values. */
    public static Validity parse(Object from, Object to) {
        return new Validity(parseMillis(from), parseMillis(to));
    }

    /** The window of an element that carries the convention properties (missing or null means unbounded). */
    public static Validity ofProperties(java.util.Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return ALWAYS;
        }
        Object from = properties.get(FROM_PROPERTY);
        Object to = properties.get(TO_PROPERTY);
        if (from == null && to == null) {
            return ALWAYS;
        }
        return new Validity(parseMillis(from), parseMillis(to));
    }

    /**
     * The index as it stood at {@code instant}: the entities and relations whose window contains it. The
     * chunks stay - a citation to a document is not itself time-versioned, and dropping it would make the
     * remaining answers uncitable.
     */
    public static AgentIndex at(AgentIndex index, Long instant) {
        if (index == null || instant == null) {
            return index;
        }
        List<AgentIndex.Entity> entities = index.entities().stream()
                .filter(entity -> entity.validAt(instant))
                .toList();
        List<AgentIndex.Relation> relations = index.relations().stream()
                .filter(relation -> relation.validAt(instant))
                .toList();
        return new AgentIndex(index.graph(), index.embedding(), index.dimensions(), index.createdAt(),
                entities, relations, index.chunks(), index.quality());
    }
}
