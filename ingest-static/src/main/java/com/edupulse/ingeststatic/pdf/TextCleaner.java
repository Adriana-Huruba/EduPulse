package com.edupulse.ingeststatic.pdf;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cleans text extracted from PDFs so chunks contain no broken or garbled text.
 *
 * <ul>
 *   <li>Unicode NFKC normalisation (ligatures such as "ﬁ" become "fi", full-width forms, ...).</li>
 *   <li>Removal of control characters, soft hyphens and zero-width characters.</li>
 *   <li>Re-joining of words hyphenated at a line break ("infor-\nmation" becomes "information").</li>
 *   <li>Whitespace normalisation, keeping paragraph breaks (blank lines).</li>
 *   <li>Removal of running headers, footers and page numbers repeated across pages.</li>
 *   <li>Pages that are mostly undecodable glyphs (fonts without a Unicode mapping) are dropped.</li>
 * </ul>
 *
 * <p>All patterns are linear (no nested quantifiers), so they are safe against ReDoS.
 */
public final class TextCleaner {

    private static final Logger log = LoggerFactory.getLogger(TextCleaner.class);

    /** Pages where more than this share of the characters cannot be decoded are skipped. */
    static final double MAX_GARBLED_RATIO = 0.3;
    /** Number of lines at the top and bottom of a page checked for running headers and footers. */
    private static final int EDGE_LINES = 2;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final Pattern INVISIBLE = Pattern.compile("[\\u00AD\\u200B-\\u200F\\u2060\\uFEFF]");
    private static final Pattern HYPHEN_BREAK = Pattern.compile("(\\p{L})-\\n(\\p{Ll})");
    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[\\t\\x0B\\f\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000 ]+");
    private static final Pattern SPACE_AROUND_NEWLINE = Pattern.compile(" ?\\n ?");
    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "(?i)(?:page\\s{0,3})?\\d{1,4}(?:\\s{0,3}(?:/|of)\\s{0,3}\\d{1,4})?|-\\s?\\d{1,4}\\s?-");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private TextCleaner() {
    }

    /** Cleans every page, then removes headers and footers repeated across pages. */
    public static List<PageText> cleanPages(List<PageText> pages) {
        List<PageText> cleaned = new ArrayList<>(pages.size());
        for (PageText page : pages) {
            String text = page.text() == null ? "" : page.text();
            double garbled = garbledRatio(text);
            if (garbled > MAX_GARBLED_RATIO) {
                log.warn("Skipping page {}: {}% of its characters cannot be decoded (font without Unicode mapping?)",
                        page.pageNumber(), Math.round(garbled * 100));
                text = "";
            }
            cleaned.add(new PageText(page.pageNumber(), clean(text)));
        }
        return removeRunningHeadersAndFooters(cleaned);
    }

    /** Cleans the text of a single page. */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replace("\uFFFD", "");
        text = INVISIBLE.matcher(text).replaceAll("");
        text = CONTROL.matcher(text).replaceAll("");
        text = HORIZONTAL_SPACE.matcher(text).replaceAll(" ");
        text = SPACE_AROUND_NEWLINE.matcher(text).replaceAll("\n");
        text = HYPHEN_BREAK.matcher(text).replaceAll("$1$2");
        text = MANY_NEWLINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    /** Share of non-whitespace characters that are replacement characters or private-use glyphs. */
    public static double garbledRatio(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int visible = 0;
        int garbled = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            visible++;
            int type = Character.getType(c);
            if (c == '\uFFFD' || type == Character.PRIVATE_USE || type == Character.UNASSIGNED
                    || (type == Character.CONTROL && c != '\n' && c != '\t')) {
                garbled++;
            }
        }
        return visible == 0 ? 0 : (double) garbled / visible;
    }

    /**
     * Removes lines that appear at the top or bottom of most pages (running headers and footers,
     * possibly with a changing page number) and stand-alone page numbers.
     */
    static List<PageText> removeRunningHeadersAndFooters(List<PageText> pages) {
        Set<String> repeated = new HashSet<>();
        if (pages.size() >= 3) {
            Map<String, Integer> counts = new HashMap<>();
            for (PageText page : pages) {
                Set<String> keys = new HashSet<>();
                for (String line : edgeLines(page.text())) {
                    String key = key(line);
                    if (!key.isEmpty()) {
                        keys.add(key);
                    }
                }
                keys.forEach(k -> counts.merge(k, 1, Integer::sum));
            }
            int threshold = Math.max(3, (int) Math.ceil(pages.size() * 0.6));
            counts.forEach((k, count) -> {
                if (count >= threshold) {
                    repeated.add(k);
                }
            });
        }

        List<PageText> result = new ArrayList<>(pages.size());
        for (PageText page : pages) {
            String[] lines = page.text().split("\n", -1);
            List<Integer> nonBlank = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].isBlank()) {
                    nonBlank.add(i);
                }
            }
            Set<Integer> edges = new HashSet<>();
            for (int i = 0; i < Math.min(EDGE_LINES, nonBlank.size()); i++) {
                edges.add(nonBlank.get(i));
                edges.add(nonBlank.get(nonBlank.size() - 1 - i));
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                boolean drop = edges.contains(i)
                        && (repeated.contains(key(line)) || PAGE_NUMBER.matcher(line.strip()).matches());
                if (!drop) {
                    text.append(line).append('\n');
                }
            }
            result.add(new PageText(page.pageNumber(), MANY_NEWLINES.matcher(text).replaceAll("\n\n").strip()));
        }
        return result;
    }

    private static List<String> edgeLines(String text) {
        List<String> nonBlank = text.lines().filter(l -> !l.isBlank()).toList();
        if (nonBlank.size() <= 2 * EDGE_LINES) {
            return nonBlank;
        }
        List<String> edges = new ArrayList<>(nonBlank.subList(0, EDGE_LINES));
        edges.addAll(nonBlank.subList(nonBlank.size() - EDGE_LINES, nonBlank.size()));
        return edges;
    }

    private static String key(String line) {
        String stripped = line.strip();
        if (stripped.length() > 120) {
            return "";
        }
        return DIGITS.matcher(stripped.toLowerCase(Locale.ROOT)).replaceAll("#");
    }
}
