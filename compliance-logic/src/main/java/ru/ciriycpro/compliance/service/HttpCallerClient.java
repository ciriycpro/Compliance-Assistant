package ru.ciriycpro.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP-реализация CallerPort. POST {base}/send-tg {chat_id, text}.
 * v1: только Telegram. WA/email/унифицированный /dispatch — позже, не трогая вызывающих.
 */
@Component
public class HttpCallerClient implements CallerPort {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper om = new ObjectMapper();

    public HttpCallerClient(@Value("${caller.base-url:http://127.0.0.1:3000}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public String sendTelegram(long chatId, String text) {
        try {
            String body = om.writeValueAsString(Map.of("chat_id", chatId, "text", text));
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/send-tg"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new RuntimeException("caller /send-tg HTTP " + resp.statusCode() + ": " + resp.body());
            }
            return resp.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("caller /send-tg failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String sendWhatsApp(String number, String text) {
        try {
            String body = om.writeValueAsString(Map.of("number", number, "text", text));
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/send-wa"))
                    .timeout(Duration.ofSeconds(120))   // Caller поднимает Chrome + ждёт ~60с
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new RuntimeException("caller /send-wa HTTP " + resp.statusCode() + ": " + resp.body());
            }
            return resp.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("caller /send-wa failed: " + e.getMessage(), e);
        }
    }
}
