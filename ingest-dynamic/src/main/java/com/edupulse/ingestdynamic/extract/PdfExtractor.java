package com.edupulse.ingestdynamic.extract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Extracts text from a PDF, one section per page so chunks keep their page number. */
@Component
public class PdfExtractor {

	public ExtractedContent extract(byte[] pdfBytes) {
		try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			List<ExtractedContent.Section> sections = new ArrayList<>();
			for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
				stripper.setStartPage(page);
				stripper.setEndPage(page);
				String text = TextNormalizer.normalize(stripper.getText(pdf));
				if (!text.isBlank()) {
					sections.add(new ExtractedContent.Section(page, text));
				}
			}
			String title = pdf.getDocumentInformation() == null ? null : pdf.getDocumentInformation().getTitle();
			return new ExtractedContent(title == null ? "" : title.strip(), sections);
		}
		catch (IOException ex) {
			throw new ExtractionException("Could not read PDF: " + ex.getMessage(), ex);
		}
	}

}
