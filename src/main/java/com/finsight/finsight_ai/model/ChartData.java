package com.finsight.finsight_ai.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChartData {

    private String year;

    @JsonAlias({
            "profit",
            "revenue",
            "sales",
            "income",
            "amount",
            "metric"
    })
    private Double value;
}
