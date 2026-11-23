package com.edupulse.ingestdynamic.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Builds small PDFs for tests. */
public final class TestPdfs {

	private TestPdfs() {
	}

	/** A PDF with one line of text per page. */
	public static byte[] withPages(String... pageTexts) throws IOException {
		try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (String text : pageTexts) {
				PDPage page = new PDPage();
				doc.addPage(page);
				try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
					stream.beginText();
					stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					stream.newLineAtOffset(50, 700);
					stream.showText(text);
					stream.endText();
				}
			}
			doc.save(out);
			return out.toByteArray();
		}
	}

}
