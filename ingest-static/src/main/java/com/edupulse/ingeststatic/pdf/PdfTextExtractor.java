package com.edupulse.ingeststatic.pdf;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.edupulse.ingeststatic.exception.DocumentParsingException;

/**
 * Extracts the text of a PDF page by page with Apache PDFBox.
 *
 * <p>Each page is first analysed by {@link ColumnLayoutAnalyzer}. Two-column pages are read
 * region by region (full-width bands, then left column, then right column) so the columns are
 * not interleaved. Paragraph breaks detected by PDFBox are kept as blank lines, which the
 * structure-aware chunker relies on.
 */
@Component
public class PdfTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /**
     * @param pdf    raw PDF bytes
     * @param source name used in log and error messages
     * @return one entry per page, in page order (pages without text have an empty string)
     * @throws DocumentParsingException when the bytes are not a readable PDF
     */
    public List<PageText> extract(byte[] pdf, String source) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new DocumentParsingException("PDF '" + source + "' has no pages");
            }
            List<PageText> pages = new ArrayList<>(pageCount);
            int multiColumnPages = 0;
            for (int i = 0; i < pageCount; i++) {
                int pageNumber = i + 1;
                PDPage page = document.getPage(i);
                List<Rectangle2D> regions = ColumnLayoutAnalyzer.regions(
                        collectPositions(document, pageNumber),
                        page.getCropBox().getWidth(),
                        page.getRotation());
                String text;
                if (regions.isEmpty()) {
                    text = extractWholePage(document, pageNumber);
                } else {
                    multiColumnPages++;
                    text = extractRegions(page, regions);
                }
                pages.add(new PageText(pageNumber, text));
            }
            log.debug("Extracted {} page(s) from '{}' ({} multi-column)", pageCount, source, multiColumnPages);
            return pages;
        } catch (InvalidPasswordException e) {
            throw new DocumentParsingException("PDF '" + source + "' is password protected", e);
        } catch (IOException e) {
            throw new DocumentParsingException("PDF '" + source + "' could not be parsed: " + e.getMessage(), e);
        }
    }

    private static List<TextPosition> collectPositions(PDDocument document, int pageNumber) throws IOException {
        PositionCollector collector = new PositionCollector();
        collector.setStartPage(pageNumber);
        collector.setEndPage(pageNumber);
        collector.getText(document);
        return collector.positions;
    }

    private static String extractWholePage(PDDocument document, int pageNumber) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        configure(stripper);
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        return stripper.getText(document);
    }

    private static String extractRegions(PDPage page, List<Rectangle2D> regions) throws IOException {
        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
        configure(stripper);
        for (int i = 0; i < regions.size(); i++) {
            stripper.addRegion(regionName(i), regions.get(i));
        }
        stripper.extractRegions(page);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < regions.size(); i++) {
            String regionText = stripper.getTextForRegion(regionName(i));
            if (regionText != null && !regionText.isBlank()) {
                text.append(regionText.strip()).append("\n\n");
            }
        }
        return text.toString();
    }

    private static String regionName(int index) {
        return "region-" + index;
    }

    private static void configure(PDFTextStripper stripper) {
        stripper.setSortByPosition(true);
        stripper.setLineSeparator("\n");
        stripper.setParagraphStart("");
        // An extra line break after each paragraph produces the blank line used as paragraph separator.
        stripper.setParagraphEnd("\n");
        stripper.setPageStart("");
        stripper.setPageEnd("\n");
    }

    /** Collects the glyph positions of a page without producing any text. */
    private static final class PositionCollector extends PDFTextStripper {

        private final List<TextPosition> positions = new ArrayList<>();

        PositionCollector() throws IOException {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            positions.add(text);
        }
    }
}
