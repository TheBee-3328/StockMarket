package net.kroia.stockmarket.stockmarket.market;

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
    void parsesFinnhubCurrentPriceAndUnixTimestamp() throws Exception {
        AtomicReference<URI> requestedUri = new AtomicReference<>();
        RealStockPriceFeed feed = feed(request -> {
            requestedUri.set(request.uri());
            return new RealStockPriceFeed.HttpResponseData(200, """
                    {"c": 123.45, "t": 1700000000, "h": 124.0, "l": 120.0, "o": 121.0, "pc": 122.0}
                    """);
        });
        try {
            RealStockPriceFeed.FetchResult result = feed.fetchQuote("AAPL");

            assertFalse(result.rateLimited());
            assertNotNull(result.quote());
            assertEquals(123.45, result.quote().price(), 0.000001);
            assertEquals(1_700_000_000_000L, result.quote().timestamp());
            assertEquals("https://finnhub.io/api/v1/quote?symbol=AAPL&token=test-token",
                    requestedUri.get().toString());
        } finally {
            feed.close();
        }
    }

    @Test
    void rejectsZeroPriceWithoutReplacingLastPrice() throws Exception {
        RealStockPriceFeed feed = feed(request ->
                new RealStockPriceFeed.HttpResponseData(200, "{\"c\":0,\"t\":1700000000}"));
        try {
            RealStockPriceFeed.FetchResult result = feed.fetchQuote("AAPL");
            assertNull(result.quote());
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
            assertEquals(0, requests.get(), "Empty default mappings and placeholder token must be inert");
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
