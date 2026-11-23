package com.edupulse.ingestdynamic.web;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.edupulse.ingestdynamic.ingest.BatchResult;
import com.edupulse.ingestdynamic.ingest.IngestionService;
import com.edupulse.ingestdynamic.ingest.SourceResult;

class IngestControllerTest {

	private IngestionService service;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		service = mock(IngestionService.class);
		mvc = MockMvcBuilders.standaloneSetup(new IngestController(service)).build();
	}

	@Test
	void returnsPerSourceResults() throws Exception {
		when(service.ingestAll(anyList())).thenReturn(BatchResult.of(List.of(
				new SourceResult("https://example.org/a", SourceResult.Status.OK, 1, 4, null),
				SourceResult.failed("https://example.org/b", "HTTP 404 from https://example.org/b"))));

		mvc.perform(post("/ingest/urls")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sources":[{"url":"https://example.org/a"},{"url":"https://example.org/b","type":"HTML"}]}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.ok").value(1))
				.andExpect(jsonPath("$.failed").value(1))
				.andExpect(jsonPath("$.results[0].status").value("OK"))
				.andExpect(jsonPath("$.results[0].chunks").value(4))
				.andExpect(jsonPath("$.results[1].status").value("FAILED"))
				.andExpect(jsonPath("$.results[1].error").value("HTTP 404 from https://example.org/b"));
		verify(service).ingestAll(anyList());
	}

	@Test
	void emptySourceListIsRejected() throws Exception {
		mvc.perform(post("/ingest/urls").contentType(MediaType.APPLICATION_JSON).content("{\"sources\":[]}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void blankUrlIsRejected() throws Exception {
		mvc.perform(post("/ingest/urls").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sources\":[{\"url\":\"  \"}]}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void unknownSourceTypeIsRejected() throws Exception {
		mvc.perform(post("/ingest/urls").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sources\":[{\"url\":\"https://example.org\",\"type\":\"FTP\"}]}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

}
