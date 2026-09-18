package com.pengcheng.phoenixgraph.fuzzy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * The key a pattern produces, turned into something a vertex id and a bin can hold.
 *
 * <p>The key is what makes two accounts land on the same link node, so it has to be
 * <b>deterministic</b> (the same context always gives the same node) and <b>safe</b> (it becomes
 * part of a record key). The fragments are lower cased, runs of anything that is not a letter, a
 * digit, {@code .}, {@code _} or {@code -} become one {@code _}, and a key longer than
 * {@value #MAX_LENGTH} characters is replaced by a hash of itself - still deterministic, and short
 * enough to read in a UI.
 */
public final class FuzzyKey {

    /** The longest key that is kept as it is; a longer one is hashed. */
    public static final int MAX_LENGTH = 120;

    private FuzzyKey() {
    }

    /**
     * Normalises a raw key.
     *
     * @param raw the key as the pattern produced it (never blank: an empty fragment means the
     *            pattern does not apply, which the caller checks first)
     */
    public static String of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new FuzzyPatternException("A fuzzy key must not be blank");
        }
        StringBuilder out = new StringBuilder(raw.length());
        boolean lastWasSeparator = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = Character.toLowerCase(raw.charAt(i));
            boolean safe = Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-';
            if (safe) {
                out.append(c);
                lastWasSeparator = false;
            } else if (!lastWasSeparator) {
                out.append('_');
                lastWasSeparator = true;
            }
        }
        String key = trimSeparators(out.toString());
        if (key.length() > MAX_LENGTH) {
            return key.substring(0, 24) + "~" + sha256(key).substring(0, 32);
        }
        return key;
    }

    /** Whether the text is a key this class produces (for validating an id written by hand). */
    public static boolean looksNormalised(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_LENGTH + 34) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == '~')) {
                return false;
            }
        }
        return true;
    }

    private static String trimSeparators(String key) {
        int start = 0;
        int end = key.length();
        while (start < end && key.charAt(start) == '_') {
            start++;
        }
        while (end > start && key.charAt(end - 1) == '_') {
            end--;
        }
        return key.substring(start, end).toLowerCase(Locale.ROOT);
    }

    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
