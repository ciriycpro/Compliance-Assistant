package ru.ciriycpro.compliance.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS policy v1.0 — явно закрытый.
 *
 * На v1.0 fronted UI нет, все вызовы — server-to-server (orchestrator -> compliance-logic).
 * CORS будет открыт явно для конкретных origins когда появится UI.
 *
 * См. DEC-017 Уровень 0 — CORS закрыт по дефолту.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins()
                .allowedMethods()
                .allowedHeaders()
                .maxAge(0);
    }
}
