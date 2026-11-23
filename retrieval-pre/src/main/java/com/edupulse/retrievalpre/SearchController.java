package com.edupulse.retrievalpre;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private static final Set<String> PARAMETERS = Set.of("q", "filter", "filterMode");

    private final QueryNormalizer normalizer;
    private final ChunkSearchRepository repository;

    SearchController(QueryNormalizer normalizer, ChunkSearchRepository repository) {
        this.normalizer = normalizer;
        this.repository = repository;
    }

    @GetMapping("/search")
    SearchResponse search(@RequestParam MultiValueMap<String, String> parameters) {
        if (!PARAMETERS.containsAll(parameters.keySet())) {
            throw new MetadataFilter.InvalidFilter("Unknown parameter. Use q, filter or filterMode.");
        }
        String original = single(parameters.get("q"));
        MetadataFilter filter = MetadataFilter.parse(parameters.get("filter"), filterMode(parameters.get("filterMode")));
        String normalized = normalizer.normalize(original);
        log.info("Search query=\"{}\", filterCount={}", QueryNormalizer.logValue(original), filter.conditions().size());
        return new SearchResponse(normalized, repository.search(normalized, filter));
    }

    @ExceptionHandler(MetadataFilter.InvalidFilter.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    FilterError invalidFilter(MetadataFilter.InvalidFilter error) {
        log.warn("Rejected metadata filter: {}", error.getMessage());
        return new FilterError("invalid_filter", error.getMessage(), List.of(MetadataFilter.VALID_FIELDS.split(", ")));
    }

    @ExceptionHandler(QueryNormalizer.InvalidQuery.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    QueryError invalidQuery(QueryNormalizer.InvalidQuery error) {
        log.warn("Rejected search query: {}", error.getMessage());
        return new QueryError("invalid_query", error.getMessage());
    }

    private static String single(List<String> queries) {
        if (queries == null || queries.size() != 1) {
            throw new QueryNormalizer.InvalidQuery("Provide q exactly once.");
        }
        return queries.getFirst();
    }

    private static String filterMode(List<String> modes) {
        if (modes == null) {
            return null;
        }
        if (modes.size() != 1) {
            throw new MetadataFilter.InvalidFilter("Provide filterMode at most once.");
        }
        return modes.getFirst();
    }

    record SearchResponse(String normalizedQuery, List<ChunkSearchRepository.Chunk> results) {}

    record FilterError(String code, String message, List<String> validFields) {}

    record QueryError(String code, String message) {}
}
