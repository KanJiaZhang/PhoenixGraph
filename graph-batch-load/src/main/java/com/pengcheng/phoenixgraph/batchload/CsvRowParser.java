package com.pengcheng.phoenixgraph.batchload;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180-style CSV row parser.
 *
 * <p>Handles fields separated by commas, quoted fields (containing commas) and
 * escaped quotes ({@code ""}). It operates line-by-line, so a quoted field must
 * not span multiple lines.
 */
public final class CsvRowParser {

    private final BufferedReader reader;

    CsvRowParser(BufferedReader reader) {
        this.reader = reader;
    }

    /** Reads the next row, or {@code null} at end of input. */
    List<String> nextRow() throws IOException {
        String line = reader.readLine();
        return line == null ? null : parseLine(line);
    }

    public static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
