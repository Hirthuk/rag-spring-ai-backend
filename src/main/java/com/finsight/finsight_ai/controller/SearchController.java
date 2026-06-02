package com.finsight.finsight_ai.controller;

import com.finsight.finsight_ai.model.SearchRequest;
import com.finsight.finsight_ai.model.SearchResponse;
import com.finsight.finsight_ai.service.search.TavilySearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final TavilySearchService
            tavilySearchService;

    @PostMapping("/test")
    public SearchResponse search(
            @RequestBody SearchRequest request
    ) {

        String answer =
                tavilySearchService.search(
                        request.getQuery()
                );

        return new SearchResponse(answer);
    }
}

