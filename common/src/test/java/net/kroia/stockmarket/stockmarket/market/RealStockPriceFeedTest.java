package net.kroia.stockmarket.stockmarket.market;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RealStockPriceFeedTest {
    @TempDir
    Path tempDir;

    private Path configPath(String json) throws IOException {
        Path path = tempDir.resolve("real-stocks.json");
        Files.writeString(path, json);
        return path;
    }

    private String configWithMappings() {
        return """
                {
                  "enabled": true,
                  "apiKey": "test-token",
                  "refreshSeconds": 60,
                  "mappings": [
                    { "ticker": "AAPL", "itemId": "examplemod:aapl_share", "scale": 1.0 },
                    { "ticker": "MSFT", "itemId": "examplemod:msft_share", "scale": 1.0 }
                  ]
                }
                """;
    }

    private RealStockPriceFeed feed(RealStockPriceFeed.QuoteHttpTransport transport) throws IOException {
        return new RealStockPriceFeed(null, null, configPath(configWithMappings()), transport);
    }

    @Test
    void parsesNewestCompletedFinnhubOhlcCandle() throws Exception {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        long mostRecentCompletedStart = (nowSeconds / 60L) * 60L - 60L;
        AtomicReference<URI> requestedUri = new AtomicReference<>();
        RealStockPriceFeed feed = feed(request -> {
            requestedUri.set(request.uri());
            return new RealStockPriceFeed.HttpResponseData(200, """
                    {"s":"ok",
                     "t":[%d,%d],
                     "o":[120.0,123.0],
                     "h":[124.0,126.0],
                     "l":[119.0,122.0],
                     "c":[122.0,125.25],
                     "v":[1000,2000]}
                    """.formatted(mostRecentCompletedStart - 60L, mostRecentCompletedStart));
        });
        try {
            RealStockPriceFeed.FetchResult result = feed.fetchCandle("AAPL");

            assertFalse(result.rateLimited());
            assertNotNull(result.candle());
            assertEquals(123.0, result.candle().open(), 0.000001);
            assertEquals(126.0, result.candle().high(), 0.000001);
            assertEquals(122.0, result.candle().low(), 0.000001);
            assertEquals(125.25, result.candle().close(), 0.000001);
            assertEquals(mostRecentCompletedStart * 1000L, result.candle().timestampMs());
            assertTrue(requestedUri.get().toString().startsWith(
                    "https://finnhub.io/api/v1/stock/candle?symbol=AAPL&resolution=1&from="));
            assertTrue(requestedUri.get().getQuery().contains("&to="));
            assertTrue(requestedUri.get().getQuery().endsWith("&token=test-token"));
        } finally {
            feed.close();
        }
    }

    @Test
    void rejectsInvalidOhlcCandleWithoutReplacingLastPrice() throws Exception {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        long completedStart = (nowSeconds / 60L) * 60L - 60L;
        RealStockPriceFeed feed = feed(request ->
                new RealStockPriceFeed.HttpResponseData(200,
                        """
                        {"s":"ok","t":[%d],"o":[123.0],"h":[124.0],"l":[120.0],"c":[0.0]}
                        """.formatted(completedStart)));
        try {
            RealStockPriceFeed.FetchResult result = feed.fetchCandle("AAPL");
            assertNull(result.candle());
            assertFalse(result.rateLimited());
        } finally {
            feed.close();
        }
    }

    @Test
    void malformedJsonFailsClosed() throws Exception {
        RealStockPriceFeed feed = feed(request ->
                new RealStockPriceFeed.HttpResponseData(200, "{not-json"));
        try {
            RealStockPriceFeed.FetchResult result = feed.fetchQuote("AAPL");
            assertNull(result.quote());
            assertFalse(result.rateLimited());
        } finally {
            feed.close();
        }
    }

    @Test
    void http429StopsTheRemainingBatchAndStartsBackoff() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> firstSymbol = new AtomicReference<>();
        RealStockPriceFeed feed = feed(request -> {
            requests.incrementAndGet();
            firstSymbol.set(request.uri().getQuery());
            return new RealStockPriceFeed.HttpResponseData(429, "rate limit");
        });
        try {
            feed.performRefreshBatch();

            assertEquals(1, requests.get(), "A 429 on the first symbol must stop the batch");
            assertEquals("symbol=AAPL&token=test-token", firstSymbol.get());
        } finally {
            feed.close();
        }
    }

    @Test
    void quotaErrorInHttp200StopsTheRemainingBatch() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        RealStockPriceFeed feed = feed(request -> {
            requests.incrementAndGet();
            return new RealStockPriceFeed.HttpResponseData(200, """
                    {"error":"API limit reached; quota exceeded"}
                    """);
        });
        try {
            feed.performRefreshBatch();

            assertEquals(1, requests.get(), "A quota error must stop the rest of this request batch");
        } finally {
            feed.close();
        }
    }

    @Test
    void defaultConfigDoesNotSendRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        Path missingConfig = tempDir.resolve("default-config").resolve("real-stocks.json");
        RealStockPriceFeed feed = new RealStockPriceFeed(null, null, missingConfig, request -> {
            requests.incrementAndGet();
            return new RealStockPriceFeed.HttpResponseData(200, "{\"c\":100,\"t\":1700000000}");
        });
        try {
            feed.performRefreshBatch();

            assertTrue(Files.exists(missingConfig), "The default config should be generated");
            assertEquals(0, requests.get(), "The placeholder token must keep default mappings inert");

            var generated = JsonParser.parseString(Files.readString(missingConfig)).getAsJsonObject();
            var mappings = generated.getAsJsonArray("mappings");
            assertEquals(18, mappings.size(), "The default config should include all requested markets");
            String generatedText = Files.readString(missingConfig);
            assertTrue(generatedText.contains("\"ticker\": \"GLD\""));
            assertTrue(generatedText.contains("\"ticker\": \"QQQ\""));
            assertTrue(generatedText.contains("\"ticker\": \"SPY\""));
            assertTrue(generatedText.contains("\"ticker\": \"FXE\""));
            assertTrue(generatedText.contains("\"ticker\": \"IBIT\""));
            assertTrue(generatedText.contains("\"ticker\": \"GOOGL\""));
            assertTrue(generatedText.contains("\"ticker\": \"GOOG\""));
        } finally {
            feed.close();
        }
    }

    @Test
    void closedFeedDoesNotSendRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        RealStockPriceFeed feed = feed(request -> {
            requests.incrementAndGet();
            return new RealStockPriceFeed.HttpResponseData(429, "rate limit");
        });
        feed.close();

        feed.performRefreshBatch();

        assertEquals(0, requests.get(), "A closed feed must not start another request batch");
    }
}
