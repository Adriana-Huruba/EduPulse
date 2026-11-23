package com.edupulse.ingeststatic.pdf;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.text.TextPosition;

/**
 * Detects a two-column layout on a page and returns the regions to read, in reading order.
 *
 * <p>Plain PDF text extraction sorts glyphs by line, so on a two-column page it glues line N of
 * the left column to line N of the right column and produces garbled text. This analyser looks
 * for a vertical gutter that splits many lines in two, then cuts the page into horizontal bands:
 * full-width bands (titles, abstracts, wide figures) are read as one region, column bands are
 * read left column first, then right column.
 *
 * <p>Tables are protected by a "prose" check: in real columns most lines fill the column width,
 * while table cells are usually short. When the check fails the page is read as a single region.
 *
 * <p>Limitations: only two columns, only unrotated pages with horizontal text.
 */
final class ColumnLayoutAnalyzer {

    /** Half width of the empty window that must exist around a candidate gutter position (pt). */
    static final double GUTTER_HALF_WIDTH = 4.0;
    /** Pages with fewer glyphs or lines are always read as a single region. */
    static final int MIN_GLYPHS = 80;
    static final int MIN_LINES = 6;
    /** A gutter needs at least this many split lines, and this share of all lines. */
    static final int MIN_SPLIT_LINES = 5;
    static final double MIN_SPLIT_LINE_RATIO = 0.35;
    /** Share of split lines whose left part must fill most of the left column (prose check). */
    static final double MIN_FULL_LINE_RATIO = 0.5;
    static final double FULL_LINE_FILL = 0.75;
    /** Large horizontal gap inside a single column indicates table cells rather than prose (pt). */
    static final double MAX_PROSE_INTERNAL_GAP = 25.0;
    static final double MAX_TABULAR_LINE_RATIO = 0.4;

    private static final double FAR = 100_000;

    private ColumnLayoutAnalyzer() {
    }

    /**
     * @return the regions to extract in reading order, or an empty list when the page should be
     *         read as a single region
     */
    static List<Rectangle2D> regions(List<TextPosition> positions, float pageWidth, int rotation) {
        if (rotation % 360 != 0) {
            return List.of();
        }
        List<Glyph> glyphs = new ArrayList<>();
        for (TextPosition p : positions) {
            String unicode = p.getUnicode();
            if (p.getDir() != 0 || unicode == null || unicode.isBlank() || p.getWidthDirAdj() <= 0) {
                continue;
            }
            glyphs.add(new Glyph(p.getXDirAdj(), p.getXDirAdj() + p.getWidthDirAdj(), p.getYDirAdj(),
                    Math.max(1, p.getHeightDir())));
        }
        if (glyphs.size() < MIN_GLYPHS) {
            return List.of();
        }
        List<Line> lines = groupIntoLines(glyphs);
        if (lines.size() < MIN_LINES) {
            return List.of();
        }

        double width = Math.max(pageWidth, glyphs.stream().mapToDouble(Glyph::x1).max().orElse(pageWidth));
        int bestX = -1;
        int bestScore = 0;
        for (int x = (int) (0.3 * width); x <= (int) (0.7 * width); x++) {
            int score = 0;
            for (Line line : lines) {
                if (line.isSplitAt(x - GUTTER_HALF_WIDTH, x + GUTTER_HALF_WIDTH)) {
                    score++;
                }
            }
            boolean closerToCentre = Math.abs(x - width / 2) < Math.abs(bestX - width / 2);
            if (score > bestScore || (score == bestScore && score > 0 && closerToCentre)) {
                bestScore = score;
                bestX = x;
            }
        }
        if (bestScore < Math.max(MIN_SPLIT_LINES, MIN_SPLIT_LINE_RATIO * lines.size())) {
            return List.of();
        }

        // Real gutter edges, measured on the lines that are split at the best position.
        final int x = bestX;
        List<Line> splitLines = lines.stream()
                .filter(l -> l.isSplitAt(x - GUTTER_HALF_WIDTH, x + GUTTER_HALF_WIDTH))
                .toList();
        double gutterStart = splitLines.stream().mapToDouble(l -> l.maxX1Before(x)).max().orElse(x);
        double gutterEnd = splitLines.stream().mapToDouble(l -> l.minX0After(x)).min().orElse(x);
        double gutterCentre = (gutterStart + gutterEnd) / 2;

        // Prose check: most split lines fill their left column and do not have wide tabular cell gaps
        // inside the left or right halves.
        double leftMargin = splitLines.stream().mapToDouble(Line::minX).min().orElse(0);
        double columnWidth = gutterStart - leftMargin;
        long fullLines = splitLines.stream()
                .filter(l -> l.maxX1Before(x) >= leftMargin + FULL_LINE_FILL * columnWidth)
                .count();
        long tabularLines = splitLines.stream()
                .filter(l -> l.hasLargeInternalGap(x, MAX_PROSE_INTERNAL_GAP))
                .count();
        if (columnWidth <= 0
                || fullLines < MIN_FULL_LINE_RATIO * splitLines.size()
                || tabularLines > MAX_TABULAR_LINE_RATIO * splitLines.size()) {
            return List.of();
        }

        return toRegions(lines, gutterStart, gutterEnd, gutterCentre);
    }

    private static List<Rectangle2D> toRegions(List<Line> lines, double gutterStart, double gutterEnd,
                                               double gutterCentre) {
        // Band = run of consecutive lines of the same kind (full width or two columns).
        List<double[]> bands = new ArrayList<>(); // {spanning (1/0), firstBaseline, lastBaseline}
        for (Line line : lines) {
            boolean spanning = line.hasGlyphIn(gutterStart + 0.5, gutterEnd - 0.5);
            double kind = spanning ? 1 : 0;
            if (!bands.isEmpty() && bands.get(bands.size() - 1)[0] == kind) {
                bands.get(bands.size() - 1)[2] = line.y;
            } else {
                bands.add(new double[] {kind, line.y, line.y});
            }
        }
        if (bands.stream().noneMatch(b -> b[0] == 0)) {
            return List.of();
        }

        List<Rectangle2D> regions = new ArrayList<>();
        for (int i = 0; i < bands.size(); i++) {
            double top = i == 0 ? -FAR : (bands.get(i - 1)[2] + bands.get(i)[1]) / 2;
            double bottom = i == bands.size() - 1 ? FAR : (bands.get(i)[2] + bands.get(i + 1)[1]) / 2;
            double height = bottom - top;
            if (bands.get(i)[0] == 1) {
                regions.add(new Rectangle2D.Double(-FAR, top, 2 * FAR, height));
            } else {
                regions.add(new Rectangle2D.Double(-FAR, top, gutterCentre + FAR, height));
                regions.add(new Rectangle2D.Double(gutterCentre, top, FAR, height));
            }
        }
        return regions;
    }

    private static List<Line> groupIntoLines(List<Glyph> glyphs) {
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x0));
        List<Line> lines = new ArrayList<>();
        Line current = null;
        for (Glyph g : sorted) {
            if (current == null || Math.abs(g.y() - current.y) > Math.max(1.5, 0.3 * g.height())) {
                current = new Line(g.y());
                lines.add(current);
            }
            current.glyphs.add(g);
        }
        return lines;
    }

    private record Glyph(double x0, double x1, double y, double height) {
    }

    private static final class Line {
        final double y;
        final List<Glyph> glyphs = new ArrayList<>();

        Line(double y) {
            this.y = y;
        }

        double minX() {
            return glyphs.stream().mapToDouble(Glyph::x0).min().orElse(0);
        }

        boolean hasGlyphIn(double from, double to) {
            for (Glyph g : glyphs) {
                if (g.x0() < to && g.x1() > from) {
                    return true;
                }
            }
            return false;
        }

        /** True when the window is empty and the line has text on both sides of it. */
        boolean isSplitAt(double from, double to) {
            boolean left = false;
            boolean right = false;
            for (Glyph g : glyphs) {
                if (g.x0() < to && g.x1() > from) {
                    return false;
                }
                if (g.x1() <= from) {
                    left = true;
                } else {
                    right = true;
                }
            }
            return left && right;
        }

        double maxX1Before(double x) {
            return glyphs.stream().filter(g -> g.x1() <= x).mapToDouble(Glyph::x1).max().orElse(x);
        }

        double minX0After(double x) {
            return glyphs.stream().filter(g -> g.x0() >= x).mapToDouble(Glyph::x0).min().orElse(x);
        }

        /** True when either the left half or the right half contains a wide whitespace gap (typical of table cells). */
        boolean hasLargeInternalGap(double splitX, double maxGap) {
            Glyph prevLeft = null;
            Glyph prevRight = null;
            for (Glyph g : glyphs) {
                if (g.x1() <= splitX) {
                    if (prevLeft != null && g.x0() - prevLeft.x1() >= maxGap) {
                        return true;
                    }
                    prevLeft = g;
                } else if (g.x0() >= splitX) {
                    if (prevRight != null && g.x0() - prevRight.x1() >= maxGap) {
                        return true;
                    }
                    prevRight = g;
                }
            }
            return false;
        }
    }
}
