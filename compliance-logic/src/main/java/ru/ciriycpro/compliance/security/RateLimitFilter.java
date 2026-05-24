package ru.ciriycpro.compliance.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket rate limit per endpoint group.
 * См. DEC-017 Уровень 0 — защита от DoS / flood.
 *
 * Лимиты:
 *  - /documents POST/upload : 30 req/min (дорогой — multipart + диск + БД)
 *  - /clients POST          : 60 req/min
 *  - /actuator/metrics       : 120 req/min (для мониторинга)
 *  - default                : 600 req/min
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String key = bucketKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(request));

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("rate limit hit key={} path={} remote={}",
                key, request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(429);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
    }

    private String bucketKey(HttpServletRequest request) {
        return request.getRemoteAddr() + ":" + endpointGroup(request);
    }

    private String endpointGroup(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/documents") && "POST".equalsIgnoreCase(request.getMethod())) return "documents-upload";
        if (uri.startsWith("/clients") && "POST".equalsIgnoreCase(request.getMethod())) return "clients-create";
        if (uri.startsWith("/actuator/metrics")) return "metrics";
        return "default";
    }

    private Bucket newBucket(HttpServletRequest request) {
        String group = endpointGroup(request);
        Bandwidth limit = switch (group) {
            case "documents-upload" -> Bandwidth.simple(30, Duration.ofMinutes(1));
            case "clients-create"   -> Bandwidth.simple(60, Duration.ofMinutes(1));
            case "metrics"          -> Bandwidth.simple(120, Duration.ofMinutes(1));
            default                 -> Bandwidth.simple(600, Duration.ofMinutes(1));
        };
        return Bucket.builder().addLimit(limit).build();
    }
}
