package ru.ciriycpro.compliance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private static final String HEADER_NAME = "X-API-Key";

    private static final Set<String> WHITELIST = Set.of(
            "/actuator/health",
            "/actuator/info"
    );

    private final byte[] expectedKeyBytes;

    public ApiKeyFilter(@Value("${api.key}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("api.key is not configured (env API_KEY)");
        }
        this.expectedKeyBytes = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();

        if (WHITELIST.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader(HEADER_NAME);
        if (providedKey == null || !constantTimeEquals(providedKey, expectedKeyBytes)) {
            log.warn("unauthorized request path={} from={} ua={}",
                    path,
                    request.getRemoteAddr(),
                    request.getHeader("User-Agent"));
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String provided, byte[] expected) {
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(providedBytes, expected);
    }
}
