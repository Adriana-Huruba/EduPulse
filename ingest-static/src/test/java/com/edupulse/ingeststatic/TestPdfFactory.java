package com.edupulse.ingeststatic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Builds real PDFs in memory using Apache PDFBox for automated tests (single-column, tables,
 * two-column, multi-page with running headers/footers, and blank pages).
 */
public final class TestPdfFactory {

    private TestPdfFactory() {
    }

    /** Multi-page single-column PDF with headings, paragraphs, and running headers/footers. */
    public static byte[] multiPageDocument(String header, List<List<String>> pageParagraphs) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            for (int p = 0; p < pageParagraphs.size(); p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    if (header != null && !header.isBlank()) {
                        writeLine(cs, regular, 9, 50, 810, header);
                    }
                    float y = 760;
                    for (String block : pageParagraphs.get(p)) {
                        boolean heading = block.length() < 60 && !block.endsWith(".");
                        PDType1Font font = heading ? bold : regular;
                        float fontSize = heading ? 13 : 10.5f;
                        for (String line : wrap(block, 78)) {
                            writeLine(cs, font, fontSize, 50, y, line);
                            y -= (fontSize + 4);
                        }
                        y -= 12;
                    }
                    writeLine(cs, regular, 9, 280, 35, "Page " + (p + 1) + " of " + pageParagraphs.size());
                }
            }
            return toBytes(doc);
        }
    }

    /** PDF containing a title, intro paragraph, and a structured multi-column table. */
    public static byte[] tableDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, bold, 14, 50, 770, "1. Embedding Model Benchmarks");
                writeLine(cs, regular, 10.5f, 50, 742,
                        "This report summarizes embedding dimensions, latency, and retrieval quality for EduPulse.");

                float[] colX = {50, 195, 290, 390, 480};
                String[][] rows = {
                        {"Model", "Dimensions", "Latency", "Recall@5", "Notes"},
                        {"nomic-embed-text", "768", "18 ms", "0.86", "Default local model"},
                        {"mxbai-embed-large", "1024", "34 ms", "0.91", "Higher accuracy"},
                        {"all-minilm", "384", "9 ms", "0.78", "Fast lightweight option"},
                        {"bge-m3", "1024", "42 ms", "0.93", "Multilingual support"}
                };
                float y = 700;
                for (int r = 0; r < rows.length; r++) {
                    PDType1Font font = r == 0 ? bold : regular;
                    for (int c = 0; c < rows[r].length; c++) {
                        writeLine(cs, font, 10, colX[c], y, rows[r][c]);
                    }
                    y -= 20;
                }
                writeLine(cs, regular, 10.5f, 50, y - 20,
                        "All measurements were collected on identical hardware with batch size sixteen.");
            }
            return toBytes(doc);
        }
    }

    /**
     * Two-column PDF with a full-width title at the top, followed by dense prose in a left column
     * and a right column so column-order extraction can be verified.
     */
    public static byte[] twoColumnDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            List<String> leftLines = List.of(
                    "1. Left Column Architecture",
                    "LeftColStart: Static ingestion parses PDF",
                    "files into clean text blocks before",
                    "splitting them into overlapping chunks.",
                    "Each chunk receives deterministic metadata",
                    "so re-running the pipeline never produces",
                    "duplicate vectors in PostgreSQL pgvector.",
                    "LeftColMiddle: Structure-aware chunking",
                    "respects headings, paragraphs, and table",
                    "rows instead of cutting words arbitrarily.",
                    "This preserves semantic coherence for the",
                    "downstream retrieval services in EduPulse.",
                    "LeftColEnd: Every document keeps a stable",
                    "identifier derived from its source path."
            );
            List<String> rightLines = List.of(
                    "2. Right Column Evaluation",
                    "RightColStart: Retrieval quality depends",
                    "strongly on how cleanly multi-column pages",
                    "are extracted during ingestion.",
                    "If left and right lines are interleaved,",
                    "sentences become garbled and embedding",
                    "similarity degrades significantly.",
                    "RightColMiddle: By detecting the vertical",
                    "gutter between columns and reading the",
                    "left column completely before the right,",
                    "EduPulse produces readable chunks.",
                    "RightColEnd: Manual inspection of random",
                    "chunks confirms clean, coherent passages",
                    "suitable for citation and grounding."
            );

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Full-width title spanning across the gutter
                writeLine(cs, bold, 14, 70, 780, "EduPulse Two-Column Retrieval-Augmented Generation Study");
                writeLine(cs, regular, 10, 70, 760,
                        "Abstract: This paper evaluates structure-aware PDF ingestion across multi-column layouts.");

                float yLeft = 720;
                for (int i = 0; i < leftLines.size(); i++) {
                    writeLine(cs, i == 0 ? bold : regular, 10, 50, yLeft, leftLines.get(i));
                    yLeft -= 15;
                }
                float yRight = 720;
                for (int i = 0; i < rightLines.size(); i++) {
                    writeLine(cs, i == 0 ? bold : regular, 10, 320, yRight, rightLines.get(i));
                    yRight -= 15;
                }
            }
            return toBytes(doc);
        }
    }

    /** Valid PDF with a single blank page (no extractable text, like an un-OCR'd scan). */
    public static byte[] blankPageDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            return toBytes(doc);
        }
    }

    /** Generates a sample set of PDFs (guide, table report, and two-column paper) into the given directory. */
    public static void main(String[] args) throws IOException {
        java.nio.file.Path outDir = java.nio.file.Path.of(args.length > 0 ? args[0] : "../resources");
        java.nio.file.Files.createDirectories(outDir);
        java.nio.file.Files.write(outDir.resolve("edupulse-architecture-guide.pdf"), multiPageDocument(
                "EduPulse RAG Architecture Guide",
                List.of(
                        List.of("1. System Overview",
                                "EduPulse is a microservice-based Retrieval-Augmented Generation platform built with "
                                        + "Spring Boot, Spring Cloud, Spring AI, and PostgreSQL with pgvector. "
                                        + "Its purpose is to improve answer quality while keeping operating costs low.",
                                "1.1 Core Services",
                                "The platform exposes a single public API gateway on port 8080 backed by Eureka "
                                        + "service discovery. Four domain services implement static ingestion, dynamic "
                                        + "ingestion, pre-retrieval query processing, and post-retrieval grounding."),
                        List.of("2. Static PDF Ingestion",
                                "The ingest-static service reads PDF documents from the mounted resources directory or "
                                        + "via multipart upload. It extracts text page by page, preserves reading order "
                                        + "for multi-column layouts, cleans ligatures and hyphenation, and splits text "
                                        + "into structure-aware overlapping chunks.",
                                "2.1 Metadata Schema and Idempotency",
                                "Each stored chunk carries doc_id, version, source, page, chunk_index, checksum, "
                                        + "and embedding_model metadata. Deterministic UUIDs guarantee that re-running "
                                        + "the same PDF file never creates duplicate vectors."))));
        java.nio.file.Files.write(outDir.resolve("rag-benchmarks-tables.pdf"), tableDocument());
        java.nio.file.Files.write(outDir.resolve("edupulse-research-twocolumn.pdf"), twoColumnDocument());
    }

    private static void writeLine(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String text)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static List<String> wrap(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!current.isEmpty() && current.length() + 1 + word.length() > maxChars) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static byte[] toBytes(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}
