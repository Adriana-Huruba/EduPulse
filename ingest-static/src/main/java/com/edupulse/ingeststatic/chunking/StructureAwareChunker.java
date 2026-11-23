package com.edupulse.ingeststatic.chunking;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.edupulse.ingeststatic.config.IngestProperties;
import com.edupulse.ingeststatic.pdf.PageText;

/**
 * Splits page text into chunks that follow the structure of the document.
 *
 * <ol>
 *   <li>Each page is split into paragraphs (blank-line separated blocks).</li>
 *   <li>Short single-line paragraphs that look like headings ("2.1 Method", "INTRODUCTION",
 *       "Results And Discussion") start a new chunk once the current one is reasonably full,
 *       and become the {@code section} of the chunks that follow.</li>
 *   <li>Paragraphs are packed into chunks of at most {@code chunkSize} characters. A paragraph is
 *       never cut while it fits in a chunk. Longer paragraphs are cut at line breaks first (which
 *       keeps table rows whole), then at sentence ends, then between words.</li>
 *   <li>Each chunk starts with the last {@code overlap} characters of the previous one, snapped to
 *       a sentence or word boundary. Overlap never crosses a section boundary.</li>
 * </ol>
 *
 * <p>Sizes are measured in characters, the overlap is included in the chunk size.
 */
@Component
public class StructureAwareChunker {

    static final int MAX_HEADING_LENGTH = 100;

    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t]*\\n");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "(?i)(?:\\d{1,2}(?:\\.\\d{1,2}){0,3}\\.?|[IVXLC]{1,6}\\.|(?:chapter|section|part|appendix)\\s[\\dA-Z]{1,4}[.:]?)\\s+\\p{L}.*");
    /** Split levels for oversize paragraphs: line breaks, sentence ends, whitespace. */
    private static final Pattern[] SPLIT_LEVELS = {
            Pattern.compile("\\n"),
            Pattern.compile("(?<=[.!?;:])\\s+"),
            Pattern.compile("\\s+")
    };
    private static final Pattern HAS_ALNUM = Pattern.compile("[\\p{L}\\p{N}]");

    private final int chunkSize;
    private final int overlap;

    @Autowired
    public StructureAwareChunker(IngestProperties properties) {
        this(properties.chunkSize(), properties.chunkOverlap());
    }

    public StructureAwareChunker(int chunkSize, int overlap) {
        if (chunkSize < 50 || overlap < 0 || overlap * 2 > chunkSize) {
            throw new IllegalArgumentException(
                    "Invalid chunking settings: size=" + chunkSize + ", overlap=" + overlap);
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public int chunkSize() {
        return chunkSize;
    }

    public int overlap() {
        return overlap;
    }

    public List<TextChunk> chunk(List<PageText> pages) {
        return new Packer().pack(toBlocks(pages));
    }

    private List<Block> toBlocks(List<PageText> pages) {
        int maxBlock = Math.max(1, chunkSize - overlap - 2);
        List<Block> blocks = new ArrayList<>();
        for (PageText page : pages) {
            if (page.text() == null || page.text().isBlank()) {
                continue;
            }
            for (String paragraph : PARAGRAPH_BREAK.split(page.text())) {
                String p = paragraph.strip();
                if (p.isEmpty()) {
                    continue;
                }
                if (p.length() <= maxBlock && isHeading(p)) {
                    blocks.add(new Block(page.pageNumber(), p, true));
                } else {
                    for (String piece : split(p, maxBlock)) {
                        blocks.add(new Block(page.pageNumber(), piece, false));
                    }
                }
            }
        }
        return blocks;
    }

    /** Heuristic heading detection for a single paragraph. */
    static boolean isHeading(String paragraph) {
        String p = paragraph.strip();
        if (p.indexOf('\n') >= 0 || p.length() < 3 || p.length() > MAX_HEADING_LENGTH) {
            return false;
        }
        if (".,;:!?".indexOf(p.charAt(p.length() - 1)) >= 0) {
            return false;
        }
        String[] words = WHITESPACE.split(p);
        if (NUMBERED_HEADING.matcher(p).matches()) {
            return words.length <= 12;
        }
        if (words.length > 10) {
            return false;
        }
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        if (letters >= 3 && upper == letters) {
            return true;
        }
        if (letters < 3 || !Character.isUpperCase(p.charAt(0))) {
            return false;
        }
        int significant = 0;
        int capitalised = 0;
        for (String w : words) {
            if (w.length() > 3 && Character.isLetter(w.charAt(0))) {
                significant++;
                if (Character.isUpperCase(w.charAt(0))) {
                    capitalised++;
                }
            }
        }
        if (significant == 0) {
            return words.length <= 3;
        }
        return words.length <= 8 && capitalised >= 0.75 * significant;
    }

    /** Splits text into parts of at most {@code max} characters at the most natural boundary available. */
    static List<String> split(String text, int max) {
        return split(text.strip(), max, 0);
    }

    private static List<String> split(String text, int max, int level) {
        if (text.length() <= max) {
            return text.isEmpty() ? List.of() : List.of(text);
        }
        if (level >= SPLIT_LEVELS.length) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < text.length(); i += max) {
                parts.add(text.substring(i, Math.min(text.length(), i + max)));
            }
            return parts;
        }
        String[] pieces = SPLIT_LEVELS[level].split(text);
        if (pieces.length <= 1) {
            return split(text, max, level + 1);
        }
        String joiner = level == 0 ? "\n" : " ";
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : pieces) {
            String piece = raw.strip();
            if (piece.isEmpty()) {
                continue;
            }
            if (piece.length() > max) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                parts.addAll(split(piece, max, level + 1));
            } else if (current.isEmpty()) {
                current.append(piece);
            } else if (current.length() + joiner.length() + piece.length() <= max) {
                current.append(joiner).append(piece);
            } else {
                parts.add(current.toString());
                current.setLength(0);
                current.append(piece);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /**
     * Returns the last {@code overlap} characters of a chunk, starting at a sentence boundary when
     * one exists in that window, otherwise at a word boundary.
     */
    static String overlapTail(String text, int overlap) {
        if (overlap <= 0 || text.length() <= overlap) {
            return "";
        }
        int start = text.length() - overlap;
        int sentenceLimit = text.length() - Math.max(1, overlap / 4);
        for (int i = Math.max(1, start); i < sentenceLimit; i++) {
            char c = text.charAt(i);
            char previous = text.charAt(i - 1);
            if (c == '\n' || (Character.isWhitespace(c) && ".!?".indexOf(previous) >= 0)) {
                return text.substring(i).strip();
            }
        }
        for (int i = start; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return text.substring(i).strip();
            }
        }
        return text.substring(start).strip();
    }

    private record Block(int page, String text, boolean heading) {
    }

    /** Mutable packing state for one document. */
    private final class Packer {

        private final List<TextChunk> chunks = new ArrayList<>();
        private final StringBuilder body = new StringBuilder();
        private int bodyPageStart = -1;
        private int bodyPageEnd = -1;
        private boolean bodyHasText;
        private String carry = "";
        private int carryPage = -1;
        private String section;
        private String chunkSection;

        List<TextChunk> pack(List<Block> blocks) {
            Deque<Block> queue = new ArrayDeque<>(blocks);
            while (!queue.isEmpty()) {
                Block block = queue.poll();
                if (block.heading()) {
                    handleHeading(block);
                    continue;
                }
                int length = currentLength();
                int room = chunkSize - length - (length == 0 ? 0 : 2);
                if (block.text().length() <= room) {
                    append(block);
                    bodyHasText = true;
                } else if (bodyHasText) {
                    String tail = overlapTail(currentText(), overlap);
                    int tailPage = bodyPageEnd;
                    flush();
                    carry = tail;
                    carryPage = tailPage;
                    queue.push(block);
                } else if (!carry.isEmpty()) {
                    carry = "";
                    queue.push(block);
                } else if (room < 20 && !body.isEmpty()) {
                    // Headings filled almost the entire chunk: flush them and start fresh for the body block.
                    flush();
                    queue.push(block);
                } else {
                    // Only headings in the chunk so far: cut the block to fit the remaining room.
                    List<String> parts = split(block.text(), Math.max(1, room));
                    if (parts.size() <= 1) {
                        flush();
                        queue.push(block);
                    } else {
                        for (int i = parts.size() - 1; i >= 0; i--) {
                            queue.push(new Block(block.page(), parts.get(i), false));
                        }
                    }
                }
            }
            if (bodyHasText || (chunks.isEmpty() && !body.isEmpty())) {
                flush();
            }
            return chunks;
        }

        private void handleHeading(Block heading) {
            // A heading closes the current chunk, unless that would leave a very small chunk.
            if (bodyHasText && currentLength() >= chunkSize / 4) {
                flush();
            }
            if (!bodyHasText) {
                // New section: never carry text of the previous section into it.
                carry = "";
            }
            section = heading.text();
            int length = currentLength();
            if (length > 0 && length + 2 + heading.text().length() > chunkSize) {
                flush();
            }
            append(heading);
        }

        private void append(Block block) {
            if (body.isEmpty()) {
                // First block of a new chunk: the chunk belongs to the section in effect here.
                chunkSection = section;
            } else {
                body.append("\n\n");
            }
            body.append(block.text());
            if (bodyPageStart < 0) {
                bodyPageStart = block.page();
            }
            bodyPageEnd = block.page();
        }

        private int currentLength() {
            if (carry.isEmpty()) {
                return body.length();
            }
            return body.isEmpty() ? carry.length() : carry.length() + 1 + body.length();
        }

        private String currentText() {
            if (carry.isEmpty()) {
                return body.toString();
            }
            return body.isEmpty() ? carry : carry + "\n" + body;
        }

        private void flush() {
            String text = currentText().strip();
            if (!text.isEmpty() && HAS_ALNUM.matcher(text).find() && bodyPageStart > 0) {
                int pageStart = carry.isEmpty() ? bodyPageStart : Math.min(carryPage, bodyPageStart);
                chunks.add(new TextChunk(chunks.size(), text, pageStart, bodyPageEnd, chunkSection));
            }
            body.setLength(0);
            bodyPageStart = -1;
            bodyPageEnd = -1;
            bodyHasText = false;
            carry = "";
            carryPage = -1;
            chunkSection = null;
        }
    }
}
