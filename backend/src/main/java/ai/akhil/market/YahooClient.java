package ai.akhil.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class YahooClient {
    public record Payload(JsonNode json, Instant fetchedAt) {}
    private record Cached(Payload payload, Instant expiresAt) {}
    private final Map<String, Cached> cache = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) { return size() > 256; }
    };
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final int cacheSeconds;

    public YahooClient(ObjectMapper mapper, @Value("${market.base-url}") String baseUrl,
                       @Value("${market.cache-seconds}") int cacheSeconds) {
        this.mapper = mapper; this.baseUrl = baseUrl.replaceAll("/$", ""); this.cacheSeconds = cacheSeconds;
    }

    // Serialize provider requests and coalesce identical cache misses. Failed responses are never cached.
    public synchronized Payload get(String path, boolean fundamentals) {
        Cached cached = cache.get(path);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.payload();
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(12)).header("User-Agent", "akhil.ai/2.0 investment-research")
                    .header("Accept", "application/json").GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) throw new MarketException(HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED", "Yahoo Finance's request limit was reached. Please retry in a few minutes.");
            if (response.statusCode() == 404) throw new MarketException(HttpStatus.NOT_FOUND,
                    "NO_DATA", "No data is available for this symbol.");
            if (response.statusCode() != 200) throw MarketException.unavailable();
            JsonNode json = mapper.readTree(response.body());
            if (json == null || !json.isObject()) throw MarketException.unavailable();
            for (String root : new String[]{"chart", "timeseries", "finance"}) {
                if (json.has(root) && json.path(root).hasNonNull("error")) {
                    throw new MarketException(HttpStatus.NOT_FOUND, "NO_DATA", "The provider has no data for this request.");
                }
            }
            Instant now = Instant.now();
            Payload payload = new Payload(json, now);
            cache.put(path, new Cached(payload, now.plusSeconds(fundamentals ? 21600 : Math.max(1, cacheSeconds))));
            return payload;
        } catch (MarketException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw MarketException.unavailable(); }
        catch (Exception e) { throw MarketException.unavailable(); }
    }
}
