package com.edupulse.ingestdynamic.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edupulse.ingestdynamic.ingest.BatchResult;
import com.edupulse.ingestdynamic.ingest.IngestionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/ingest")
public class IngestController {

	private final IngestionService ingestionService;

	public IngestController(IngestionService ingestionService) {
		this.ingestionService = ingestionService;
	}

	/**
	 * Ingests a batch of internet sources. Always answers 200 with a per-source result, so
	 * one failing source never hides the others; a malformed request body is a 400.
	 */
	@PostMapping("/urls")
	public BatchResult ingestUrls(@Valid @RequestBody IngestRequest request) {
		return ingestionService.ingestAll(request.sources());
	}

}
