package com.finsight.finsight_ai.service;

import com.finsight.finsight_ai.model.ChartData;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ForecastService {

    public List<ChartData> predictRevenueForecast() {

        List<ChartData> data = new ArrayList<>();

        // Historical Data
        data.add(new ChartData("2020", 10.0));
        data.add(new ChartData("2021", 12.0));
        data.add(new ChartData("2022", 15.0));
        data.add(new ChartData("2023", 18.0));
        data.add(new ChartData("2024", 22.0));

        // Forecast
        data.add(new ChartData("2025", 25.0));
        data.add(new ChartData("2026", 28.0));

        return data;
    }
}
