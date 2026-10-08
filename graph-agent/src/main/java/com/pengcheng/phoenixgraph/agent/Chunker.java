package com.pengcheng.phoenixgraph.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The input side of the toolkit: a document (whatever it is - a chapter, a spec, a CSV of triples, a
 * ticket) becomes a list of {@link Chunk}s, and every chunk keeps a stable id.
 *
 * <p>Chunking is what makes provenance possible. An extracted entity or relation always names the chunk
 * and the sentence it was read from, and the chunk is what an answer cites - so the quality report can
 * check mechanically that a relation's sentence really contains both of its ends, and an AI answer can
 * quote the source instead of asserting a fact from nowhere.
 *
 * <p>Splitting is deliberately simple and language-agnostic: paragraphs first, then sentences within a
 * paragraph that is too long, and a hard cap so a document without punctuation still chunks. Nothing here
 * needs a tokenizer or a model, which keeps ingestion offline and reproducible.
 */
public final class Chunker {

    /** How long a chunk may be before it is split further (characters). */
    public static final int DEFAULT_MAX_CHARS = 400;

    private Chunker() {
    }

    /** One piece of one document, with the id everything downstream refers to. */
    public record Chunk(String chunkId, String documentId, String title, int ordinal, String text) {

        public Chunk {
            Objects.requireNonNull(chunkId, "chunkId");
        }

        /** A one-line citation: {@code title §3} or the document id when it has no title. */
        public String cite() {
            String name = title == null || title.isBlank() ? documentId : title;
            return name + " §" + (ordinal + 1);
        }
    }

    /** A document to ingest. {@code id} is the caller's key (a path, a ticket id, a URL). */
    public record Document(String id, String title, String text) {

        public Document {
            Objects.requireNonNull(id, "id");
            text = text == null ? "" : text;
        }
    }

    /** Splits every document into chunks, in document order. */
    public static List<Chunk> chunk(List<Document> documents) {
        return chunk(documents, DEFAULT_MAX_CHARS);
    }

    /** Splits every document into chunks of at most {@code maxChars} characters. */
    public static List<Chunk> chunk(List<Document> documents, int maxChars) {
        int limit = Math.max(80, maxChars);
        List<Chunk> chunks = new ArrayList<>();
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        for (Document document : documents) {
            if (document == null || document.text() == null || document.text().isBlank()) {
                continue;
            }
            int ordinal = ordinals.merge(document.id(), 0, (a, b) -> a + 1);
            // paragraphs first: a paragraph is usually one idea, and that is the unit a citation wants
            for (String paragraph : document.text().split("\\n\\s*\\n")) {
                String trimmed = paragraph.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                for (String piece : split(trimmed, limit)) {
                    chunks.add(new Chunk(document.id() + "#" + ordinal, document.id(), document.title(),
                            ordinal, piece));
                    ordinal += 1;
                }
            }
            ordinals.put(document.id(), ordinal);
        }
        return chunks;
    }

    /** One paragraph: sentences up to the limit, then a hard split for anything longer. */
    private static List<String> split(String paragraph, int limit) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences(paragraph)) {
            if (current.length() > 0 && current.length() + sentence.length() > limit) {
                out.add(current.toString().strip());
                current.setLength(0);
            }
            if (sentence.length() > limit) {
                if (current.length() > 0) {
                    out.add(current.toString().strip());
                    current.setLength(0);
                }
                for (int i = 0; i < sentence.length(); i += limit) {
                    out.add(sentence.substring(i, Math.min(sentence.length(), i + limit)).strip());
                }
                continue;
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            out.add(current.toString().strip());
        }
        return out;
    }

    /** Sentences of a paragraph, keeping their terminator (Chinese and ASCII punctuation both count). */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '!' || c == '?' || c == ';'
                    || (c == '.' && isSentenceEnd(text, i)) || c == '\n') {
                String sentence = current.toString().strip();
                if (!sentence.isEmpty()) {
                    out.add(sentence);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    /** Whether a dot ends a sentence rather than a number or an abbreviation (kept simple on purpose). */
    private static boolean isSentenceEnd(String text, int index) {
        if (index + 1 < text.length()) {
            char next = text.charAt(index + 1);
            if (Character.isDigit(next) || Character.isLetter(next)) {
                return false;
            }
        }
        return true;
    }
}
