package net.kroia.stockmarket.stockmarket.market;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kroia.banksystem.util.ItemID;
import net.kroia.stockmarket.StockMarketMod;
import net.kroia.stockmarket.stockmarket.marketmanager.MarketManager;
import net.kroia.stockmarket.stockmarket.marketmanager.ServerMarketManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional Finnhub quote importer for the master server.
 *
 * <p>Each ticker must be explicitly mapped to a dedicated share-token item in
 * real-stocks.json. Existing markets without a persisted matching feed-owner
 * marker are never adopted. All network I/O runs on a daemon worker and market
 * mutations are marshalled to the Minecraft server thread.
 *
 * <p>Quotes are reference/anchor prices, not a replacement order engine: player
 * limit orders stay at their configured prices and fills can move the market
 * between refreshes. A fresh external quote re-anchors the market price; the
 * normal market update then moves the virtual-liquidity window, preserving
 * overlapping levels and only filling newly exposed levels via normal policy.
 */
public final class RealStockPriceFeed implements AutoCloseable {
    private static final long MIN_REFRESH_MS = 60_000L;
    // Finnhub's free tier is commonly documented at 60 API calls/minute.
    // Pace at 55/min to leave headroom for retries and avoid edge-of-window bursts.
    private static final long MAX_CALLS_PER_MINUTE = 55L;
    private static final long MIN_INTER_SYMBOL_DELAY_MS = 1_100L;
    private static final long RATE_LIMIT_BACKOFF_MS = 5 * 60_000L;
    private static final String API_KEY_PLACEHOLDER = "REPLACE_WITH_YOUR_FINNHUB_API_TOKEN";

    private final MinecraftServer server;
    private final ServerMarketManager manager;
    private final Path configPath;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "StockMarket-RealStockFeed");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    // Populated only during construction, then immutable for this feed instance.
    private final List<Mapping> mappings = new ArrayList<>();
    private volatile String apiKey = "";
    private volatile boolean enabled = true;
    private volatile long refreshMs = MIN_REFRESH_MS;
    private volatile long nextRefreshAt;
    private volatile long lastRateLimitAt;

    private RealStockPriceFeed(MinecraftServer server, ServerMarketManager manager, Path configPath) {
        this.server = server;
        this.manager = manager;
        this.configPath = configPath;
        loadConfig();
    }

    public static RealStockPriceFeed start(MinecraftServer server, ServerMarketManager manager, Path configPath) {
        RealStockPriceFeed feed = new RealStockPriceFeed(server, manager, configPath);
        feed.nextRefreshAt = System.currentTimeMillis() + feed.effectiveRefreshIntervalMs();
        feed.refreshNow();
        return feed;
    }

    /** Called on the server thread. HTTPS requests are never performed in a tick. */
    public void tick() {
        if (closed.get() || !enabled || mappings.isEmpty() || !hasUsableApiKey()) return;
        long now = System.currentTimeMillis();
        if (now < nextRefreshAt || inFlight.get()) return;
        long backoffUntil = rateLimitBackoffUntil();
        if (now < backoffUntil) {
            nextRefreshAt = backoffUntil;
            return;
        }
        nextRefreshAt = now + effectiveRefreshIntervalMs();
        refreshNow();
    }

    /**
     * Respect Finnhub's per-minute request limit. At most 55 requests are budgeted
     * per minute to leave headroom beneath the commonly documented free-tier cap
     * of 60 calls/minute. With five tickers and a 60-second configured interval,
     * quotes can refresh each minute instead of being throttled by a daily quota.
     */
    private long effectiveRefreshIntervalMs() {
        long configured = Math.max(MIN_REFRESH_MS, refreshMs);
        if (mappings.isEmpty()) return configured;
        long requestBudgetInterval = (mappings.size() * 60_000L
                + MAX_CALLS_PER_MINUTE - 1) / MAX_CALLS_PER_MINUTE;
        return Math.max(configured, requestBudgetInterval);
    }

    private long rateLimitBackoffUntil() {
        long limitedAt = lastRateLimitAt;
        return limitedAt == 0 ? 0 : limitedAt + RATE_LIMIT_BACKOFF_MS;
    }

    private boolean hasUsableApiKey() {
        String key = apiKey;
        if (key == null || key.isBlank()) return false;
        String normalized = key.toUpperCase(Locale.ROOT);
        // Treat both the current and older generated placeholder spellings as inert.
        boolean looksLikePlaceholder = normalized.startsWith("REPLACE_WITH")
                || normalized.startsWith("PUT_YOUR")
                || (normalized.contains("YOUR")
                    && (normalized.contains("API_KEY") || normalized.contains("APIKEY")));
        return !API_KEY_PLACEHOLDER.equals(key) && !looksLikePlaceholder;
    }

    private void refreshNow() {
        if (closed.get() || !enabled || mappings.isEmpty() || !hasUsableApiKey()
                || !inFlight.compareAndSet(false, true)) return;
        try {
            worker.submit(() -> {
                try {
                    long backoffUntil = rateLimitBackoffUntil();
                    if (closed.get() || System.currentTimeMillis() < backoffUntil) return;

                    for (int i = 0; i < mappings.size(); i++) {
                        if (closed.get() || Thread.currentThread().isInterrupted()) return;
                        if (System.currentTimeMillis() < rateLimitBackoffUntil()) return;

                        Mapping mapping = mappings.get(i);
                        FetchResult result = fetchQuote(mapping.ticker);
                        if (result.rateLimited()) {
                            // Stop the batch immediately. Do not hit the next symbol.
                            nextRefreshAt = rateLimitBackoffUntil();
                            return;
                        }
                        if (closed.get()) return;
                        if (result.quote() != null) {
                            Quote quote = result.quote();
                            try {
                                // The worker only obtains data. All game state is read/written
                                // from this task on the server thread, and stale tasks no-op.
                                server.execute(() -> {
                                    if (!closed.get()) applyQuote(mapping, quote);
                                });
                            } catch (RejectedExecutionException e) {
                                if (!closed.get()) {
                                    StockMarketMod.LOGGER.warn("[RealStockFeed] Server stopped before quote could be applied.");
                                }
                                return;
                            }
                        }
                        if (i + 1 < mappings.size()) Thread.sleep(MIN_INTER_SYMBOL_DELAY_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    if (!closed.get()) {
                        StockMarketMod.LOGGER.warn("[RealStockFeed] Refresh failed; retaining last valid prices: {}", e.getMessage());
                    }
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.set(false);
            if (!closed.get()) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Refresh worker is unavailable: {}", e.getMessage());
            }
        }
    }

    private FetchResult fetchQuote(String ticker) {
        try {
            String symbol = URLEncoder.encode(ticker, StandardCharsets.UTF_8);
            String key = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            URI uri = URI.create("https://finnhub.io/api/v1/quote?symbol=" + symbol + "&token=" + key);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 429) {
                recordRateLimit(ticker, "HTTP 429");
                return FetchResult.rateLimitedResult();
            }
            if (response.statusCode() / 100 != 2) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Provider HTTP status {} for {}",
                        response.statusCode(), ticker);
                return FetchResult.failure();
            }

            var parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Invalid quote response for {}", ticker);
                return FetchResult.failure();
            }
            JsonObject json = parsed.getAsJsonObject();
            if (json.has("error") || !json.has("c")) {
                String message = json.has("error") ? json.get("error").getAsString() : "response has no current-price field";
                if (isRateLimitFailure("", message)) {
                    recordRateLimit(ticker, "Finnhub API error: " + message);
                    return FetchResult.rateLimitedResult();
                }
                StockMarketMod.LOGGER.warn("[RealStockFeed] No quote for {}: {}", ticker, message);
                return FetchResult.failure();
            }

            // Finnhub's quote response uses c for current price and t for Unix seconds.
            double price = json.get("c").getAsDouble();
            if (!Double.isFinite(price) || price <= 0) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Finnhub returned no valid current price for {} (market closed or symbol unavailable).", ticker);
                return FetchResult.failure();
            }
            long timestamp = json.has("t") && json.get("t").isJsonPrimitive()
                    ? json.get("t").getAsLong() * 1000L
                    : System.currentTimeMillis();
            if (timestamp <= 0) timestamp = System.currentTimeMillis();
            return FetchResult.success(new Quote(price, timestamp));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.failure();
        } catch (Exception e) {
            if (!closed.get()) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Could not fetch {}: {}", ticker, e.getMessage());
            }
            return FetchResult.failure();
        }
    }

    private static boolean isRateLimitFailure(String code, String message) {
        String normalizedCode = code == null ? "" : code.trim();
        String normalizedMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return "429".equals(normalizedCode)
                || normalizedMessage.contains("rate limit")
                || normalizedMessage.contains("api limit")
                || normalizedMessage.contains("too many requests")
                || normalizedMessage.contains("quota")
                || normalizedMessage.contains("limit exceeded")
                || normalizedMessage.contains("maximum number of requests");
    }

    private void recordRateLimit(String ticker, String reason) {
        lastRateLimitAt = System.currentTimeMillis();
        // Keep the normal/quota-derived refresh interval if it is longer than the
        // transient rate-limit backoff; never accelerate the next batch after a 429.
        nextRefreshAt = Math.max(nextRefreshAt, lastRateLimitAt + RATE_LIMIT_BACKOFF_MS);
        StockMarketMod.LOGGER.warn("[RealStockFeed] Rate limit for {} ({}); stopping this batch and backing off before the next refresh.",
                ticker, reason);
    }

    private void applyQuote(Mapping mapping, Quote quote) {
        if (closed.get()) return;
        try {
            ItemStack stack = resolveItemStack(mapping.itemId);
            if (stack.isEmpty()) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Could not resolve item '{}' for {}",
                        mapping.itemId, mapping.ticker);
                return;
            }
            ItemID id = ItemID.getOrRegisterFromItemStackServerSide_direct(stack);
            if (id == null || !id.isValid()) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Invalid Minecraft item mapping '{}' for {}",
                        mapping.itemId, mapping.ticker);
                return;
            }

            var existing = manager.getMarket(id);
            ServerMarket stockMarket;
            if (existing != null) {
                // Ownership is saved in the market NBT, so the same ticker is allowed
                // to refresh after a restart but an unrelated/legacy market is not.
                if (!(existing instanceof ServerMarket candidate)
                        || !candidate.isOwnedByExternalPriceFeed(mapping.ticker)) {
                    if (!mapping.warnedExistingMarket) {
                        StockMarketMod.LOGGER.warn("[RealStockFeed] Mapping {} points to an existing market item {}; refusing to take it over. Use a dedicated share-token item ID.",
                                mapping.ticker, mapping.itemId);
                        mapping.warnedExistingMarket = true;
                    }
                    return;
                }
                stockMarket = candidate;
            } else {
                var created = manager.createMarket(id);
                if (!(created instanceof ServerMarket candidate)) return;
                if (!candidate.claimExternalPriceFeedOwnership(mapping.ticker)) return;
                stockMarket = candidate;
            }

            long raw = Math.max(1L, MarketManager.convertToRawAmountStatic(quote.price * mapping.scale));
            if (stockMarket.setExternalReferencePrice(mapping.ticker, raw)) {
                mapping.lastPrice = quote.price;
                mapping.lastUpdated = quote.timestamp;
            }
        } catch (Exception e) {
            if (!closed.get()) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Failed applying {}: {}", mapping.ticker, e.getMessage());
            }
        }
    }

    private static ItemStack resolveItemStack(String itemId) {
        try {
            net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.parse(itemId);
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(location);
            return item == net.minecraft.world.item.Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private void loadConfig() {
        try {
            Files.createDirectories(configPath.getParent());
            if (!Files.exists(configPath)) {
                String example = """
                        {
                          "enabled": true,
                          "apiKey": "REPLACE_WITH_YOUR_FINNHUB_API_TOKEN",
                          "refreshSeconds": 60,
                          "mappings": []
                        }
                        """;
                Files.writeString(configPath, example, StandardCharsets.UTF_8);
                StockMarketMod.LOGGER.info("[RealStockFeed] Created safe empty configuration at {}", configPath);
            }

            JsonObject root = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8)).getAsJsonObject();
            enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
            apiKey = root.has("apiKey") ? root.get("apiKey").getAsString().trim() : "";
            refreshMs = Math.max(MIN_REFRESH_MS,
                    root.has("refreshSeconds") ? Math.multiplyExact(root.get("refreshSeconds").getAsLong(), 1000L) : MIN_REFRESH_MS);

            mappings.clear();
            Set<String> mappedItemIds = new HashSet<>();
            Set<String> mappedTickers = new HashSet<>();
            if (root.has("mappings") && root.get("mappings").isJsonArray()) {
                root.getAsJsonArray("mappings").forEach(element -> {
                    if (!element.isJsonObject()) return;
                    JsonObject obj = element.getAsJsonObject();
                    String ticker = obj.has("ticker")
                            ? obj.get("ticker").getAsString().trim().toUpperCase(Locale.ROOT) : "";
                    String itemId = obj.has("itemId") ? obj.get("itemId").getAsString().trim() : "";
                    double scale = obj.has("scale") ? obj.get("scale").getAsDouble() : 1.0;
                    if (!ticker.matches("[A-Z0-9.]{1,15}")
                            || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                            || !Double.isFinite(scale) || scale <= 0) {
                        StockMarketMod.LOGGER.warn("[RealStockFeed] Skipping invalid ticker mapping.");
                        return;
                    }
                    // Never let stock symbols take over vanilla item economics, even if
                    // a server still has an older generated config from a prior build.
                    if (itemId.startsWith("minecraft:")) {
                        StockMarketMod.LOGGER.warn("[RealStockFeed] Skipping {}: vanilla item mappings are unsafe; configure a dedicated share-token item from another namespace.", ticker);
                        return;
                    }
                    if (mappedTickers.contains(ticker)) {
                        StockMarketMod.LOGGER.warn("[RealStockFeed] Skipping duplicate ticker mapping for {}.", ticker);
                        return;
                    }
                    if (!mappedItemIds.add(itemId)) {
                        StockMarketMod.LOGGER.warn("[RealStockFeed] Skipping {}: item {} is already mapped to another ticker.",
                                ticker, itemId);
                        return;
                    }
                    mappedTickers.add(ticker);
                    mappings.add(new Mapping(ticker, itemId, scale));
                });
            }
            if (enabled && !mappings.isEmpty() && !hasUsableApiKey()) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Add a valid Finnhub API token to enable configured tickers.");
            }
            if (enabled && mappings.size() > 0) {
                long effective = effectiveRefreshIntervalMs();
                if (effective > Math.max(MIN_REFRESH_MS, refreshMs)) {
                    StockMarketMod.LOGGER.info("[RealStockFeed] Using {}-second refresh interval to remain under the conservative per-minute request budget.",
                            effective / 1000L);
                }
                StockMarketMod.LOGGER.warn("[RealStockFeed] Finnhub's personal-use terms prohibit redistributing market data or derived results without written approval. Obtain written approval before displaying quotes to other players.");
            }
        } catch (IOException | RuntimeException e) {
            enabled = false;
            mappings.clear();
            StockMarketMod.LOGGER.error("[RealStockFeed] Could not load {}. Feed disabled: {}",
                    configPath, e.getMessage());
        }
    }

    @Override
    public void close() {
        // Mark closed before interrupting so callbacks already queued on the server
        // thread become no-ops. Old feed instances cannot mutate a restarted server.
        if (closed.compareAndSet(false, true)) {
            worker.shutdownNow();
        }
    }

    private static final class Mapping {
        final String ticker;
        final String itemId;
        final double scale;
        boolean warnedExistingMarket;
        volatile double lastPrice;
        volatile long lastUpdated;

        Mapping(String ticker, String itemId, double scale) {
            this.ticker = ticker;
            this.itemId = itemId;
            this.scale = scale;
        }
    }

    private record Quote(double price, long timestamp) {}

    private record FetchResult(Quote quote, boolean rateLimited) {
        static FetchResult success(Quote quote) {
            return new FetchResult(quote, false);
        }
        static FetchResult failure() {
            return new FetchResult(null, false);
        }
        static FetchResult rateLimitedResult() {
            return new FetchResult(null, true);
        }
    }
}
