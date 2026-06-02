package com.finsight.finsight_ai.service.search;

import org.springframework.stereotype.Service;

@Service
public class SearchRoutingService {

    public boolean shouldUseInternetSearch(
            String question
    ) {

        String q =
                question.toLowerCase();

        return q.contains("latest")
                || q.contains("current")
                || q.contains("today")
                || q.contains("recent")
                || q.contains("news")
                || q.contains("stock")
                || q.contains("market")
                || q.contains("growth")
                || q.contains("trend")
                || q.contains("tesla")
                || q.contains("apple")
                || q.contains("microsoft")
                || q.contains("nvidia");
    }

}
