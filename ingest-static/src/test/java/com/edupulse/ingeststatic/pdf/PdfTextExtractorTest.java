package com.edupulse.ingeststatic.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.edupulse.ingeststatic.TestPdfFactory;
import com.edupulse.ingeststatic.exception.DocumentParsingException;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void extractsTwoColumnPdfInReadingOrderWithoutInterleaving() throws Exception {
        byte[] pdf = TestPdfFactory.twoColumnDocument();

        List<PageText> pages = TextCleaner.cleanPages(extractor.extract(pdf, "two-column.pdf"));

        assertThat(pages).hasSize(1);
        String text = pages.get(0).text();
        assertThat(text).contains("EduPulse Two-Column Retrieval-Augmented Generation Study");
        assertThat(text.indexOf("LeftColStart")).isLessThan(text.indexOf("LeftColEnd"));
        assertThat(text.indexOf("LeftColEnd")).isLessThan(text.indexOf("RightColStart"));
        assertThat(text.indexOf("RightColStart")).isLessThan(text.indexOf("RightColEnd"));
    }

    @Test
    void extractsTableRowsWithoutBreakingCellsAcrossColumns() throws Exception {
        byte[] pdf = TestPdfFactory.tableDocument();

        List<PageText> pages = TextCleaner.cleanPages(extractor.extract(pdf, "tables.pdf"));

        assertThat(pages).hasSize(1);
        String text = pages.get(0).text();
        assertThat(text).contains("1. Embedding Model Benchmarks");
        assertThat(text).contains("nomic-embed-text 768 18 ms 0.86 Default local model");
        assertThat(text).contains("mxbai-embed-large 1024 34 ms 0.91 Higher accuracy");
    }

    @Test
    void throwsDocumentParsingExceptionOnCorruptedPdfBytes() {
        byte[] corrupt = "%PDF-1.7\ncorrupted-body-not-valid-xref".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> extractor.extract(corrupt, "broken.pdf"))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("broken.pdf");
    }
}
