package com.edupulse.ingestdynamic.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.edupulse.ingestdynamic.support.TestPdfs;

class PdfExtractorTest {

	private final PdfExtractor extractor = new PdfExtractor();

	@Test
	void extractsOneSectionPerPageWithPageNumbers() throws IOException {
		ExtractedContent content = extractor.extract(TestPdfs.withPages("alpha page one", "beta page two"));

		assertThat(content.sections()).hasSize(2);
		assertThat(content.sections().get(0).page()).isEqualTo(1);
		assertThat(content.sections().get(0).text()).contains("alpha page one");
		assertThat(content.sections().get(1).page()).isEqualTo(2);
		assertThat(content.sections().get(1).text()).contains("beta page two");
	}

	@Test
	void notAPdfIsRejected() {
		assertThatThrownBy(() -> extractor.extract("this is not a pdf".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(ExtractionException.class);
	}

}
