package ai.akhil.market;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static ai.akhil.market.MarketModels.*;

@RestController
@RequestMapping("/api")
public class MarketController {
    private final MarketService service;
    public MarketController(MarketService service) { this.service = service; }
    @GetMapping("/health") public Map<String, String> health() {
        return Map.of("status", "UP", "provider", "Yahoo Finance", "priceCache", "5 minutes by default", "dataMode", "external");
    }
    @GetMapping("/search") public SearchResult search(@RequestParam(defaultValue = "") String q) { return service.search(q); }
    @GetMapping("/stocks/{symbol}/quote") public Quote quote(@PathVariable String symbol) { return service.history(symbol, "1y").quote(); }
    @GetMapping("/stocks/{symbol}/history") public History history(@PathVariable String symbol, @RequestParam(defaultValue = "1y") String range) { return service.history(symbol, range); }
    @GetMapping("/stocks/{symbol}/financials") public Financials financials(@PathVariable String symbol) { return service.financials(symbol); }
}
