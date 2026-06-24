package com.finsight.finsight_ai.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    // Comma-separated list of allowed origins. Spring splits automatically.
    // Production: set ALLOWED_ORIGINS env var in ECS Task Definition
    //   (pull from Secrets Manager: finsight/prod/allowed-origins)
    // Local default: http://localhost:5173
    @Value("${cors.allowed-origins:http://localhost:5173}")
    private List<String> allowedOrigins;

    /**
     * Single CorsConfigurationSource bean used by BOTH Spring Security (preflight OPTIONS)
     * and Spring MVC (actual requests). Without this, Spring Security blocks preflight
     * requests before the MVC CORS config is ever reached.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
