package com.edupulse.ingestdynamic.web;

import java.util.List;

import com.edupulse.ingestdynamic.ingest.SourceRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record IngestRequest(@NotEmpty @Size(max = 50) List<@Valid SourceRequest> sources) {
}
