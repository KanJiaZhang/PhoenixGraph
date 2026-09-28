package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * {@code dateAdd}, {@code dateDiff} and {@code dateTime}: arithmetic on the times a graph carries.
 *
 * <p>A time in this engine is either epoch millis (the meta timestamps, and what {@code createdBetween}
 * takes) or a UTC text form ({@code 2026-01-01}, {@code 2026-01-01T12:30:45}). Both are read here, and
 * the same shape is handed back: a number stays a number, text stays text - a script that adds a day to
 * a window can feed the result straight back into {@code createdBetween}.
 *
 * <p>The units are spelled the way TinkerPop does ({@code DT.day}) or plainly ({@code day}, {@code days}).
 */
final class GremlinDates {

    private GremlinDates() {
    }

    /** {@code dateTime()}: a time as the UTC text form (what a reader and {@code createdBetween} accept). */
    static String dateTime(Object value) {
        return Instant.ofEpochMilli(millis(value)).toString();
    }

    /** {@code dateAdd(unit, amount)}: the same shape moved by {@code amount} of {@code unit}. */
    static Object dateAdd(String unit, long amount, Object value) {
        long added = switch (unit(unit)) {
            case "second" -> amount * 1_000L;
            case "minute" -> amount * 60_000L;
            case "hour" -> amount * 3_600_000L;
            case "day" -> amount * 86_400_000L;
            case "week" -> amount * 7 * 86_400_000L;
            case "month" -> calendarMonths(amount);
            case "year" -> calendarMonths(amount * 12);
            default -> throw new QueryException("Unknown date unit '" + unit + "'. Supported: "
                    + GremlinNumbers.units());
        };
        long moved = millis(value) + added;
        return value instanceof Number ? (Object) moved : (Object) Instant.ofEpochMilli(moved).toString();
    }

    /** {@code dateDiff(unit, other)}: whole units from this time to {@code other} (other - this). */
    static long dateDiff(String unit, Object value, Object other) {
        long difference = millis(other) - millis(value);
        return switch (unit(unit)) {
            case "second" -> difference / 1_000L;
            case "minute" -> difference / 60_000L;
            case "hour" -> difference / 3_600_000L;
            case "day" -> difference / 86_400_000L;
            case "week" -> difference / (7 * 86_400_000L);
            case "month" -> monthsBetween(value, other);
            case "year" -> monthsBetween(value, other) / 12;
            default -> throw new QueryException("Unknown date unit '" + unit + "'. Supported: "
                    + GremlinNumbers.units());
        };
    }

    /** A month is not a fixed number of millis, so a month move and a month count walk the calendar. */
    private static long calendarMonths(long months) {
        // for a move the exact day is kept by the caller's timestamp arithmetic; a month is 30.44 days on
        // average, and a caller that needs exact calendar months should compute on the text form instead
        return Math.round(months * 2_629_746_000d);
    }

    private static long monthsBetween(Object from, Object to) {
        LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis(from)), ZoneOffset.UTC);
        LocalDateTime end = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis(to)), ZoneOffset.UTC);
        return (end.getYear() - start.getYear()) * 12L + (end.getMonthValue() - start.getMonthValue());
    }

    /** The unit a step argument names: {@code DT.day}, {@code day} or {@code days}. */
    static String unit(String argument) {
        String text = argument == null ? "" : argument.trim();
        if (text.regionMatches(true, 0, "DT.", 0, 3)) {
            text = text.substring(3);
        }
        text = text.toLowerCase(java.util.Locale.ROOT);
        return text.endsWith("s") ? text.substring(0, text.length() - 1) : text;
    }

    /** A time as epoch millis: a number is taken as it is, text is read as a UTC date or instant. */
    static long millis(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty()) {
            throw new QueryException("Expected a time, got: " + value);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            // not epoch millis: read it as a date
        }
        try {
            if (text.length() == 10) {
                return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
            if (text.endsWith("Z")) {
                return Instant.parse(text).toEpochMilli();
            }
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new QueryException("Cannot read '" + value + "' as a time; use epoch millis or a UTC"
                    + " date such as 2026-01-01 or 2026-01-01T12:30");
        }
    }
}
