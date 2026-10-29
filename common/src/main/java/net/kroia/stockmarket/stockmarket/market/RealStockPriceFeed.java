package net.kroia.stockmarket.stockmarket.market;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kroia.banksystem.util.ItemID;
import net.kroia.stockmarket.api.integration.MarketConfig;
import net.kroia.stockmarket.minecraft.item.StockMarketItems;
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
 * Optional Finnhub OHLC candle importer for the master server.
 *
 * <p>Each ticker must be explicitly mapped to a dedicated share-token item in
 * real-stocks.json. Existing markets without a persisted matching feed-owner
 * marker are never adopted. All network I/O runs on a daemon worker and market
 * mutations are marshalled to the Minecraft server thread.
 *
 * <p>Only completed one-minute provider candles are used. Each candle is replayed
 * one interval late, simulating the intrabar path between its real open and close
 * while retaining its real high/low bounds. Player orders still execute, but their
 * fills cannot displace the authoritative external price.
 */
public final class RealStockPriceFeed implements AutoCloseable {
    private static final long MIN_REFRESH_MS = 60_000L;
    private static final long CANDLE_DURATION_MS = 60_000L;
    // Finnhub's free tier is commonly documented at 60 API calls/minute.
    // Pace at 55/min to leave headroom for retries and avoid edge-of-window bursts.
    private static final long MAX_CALLS_PER_MINUTE = 55L;
    private static final long MIN_INTER_SYMBOL_DELAY_MS = 1_100L;
    private static final long RATE_LIMIT_BACKOFF_MS = 5 * 60_000L;
    private static final String API_KEY_PLACEHOLDER = "REPLACE_WITH_YOUR_FINNHUB_API_TOKEN";

    /**
     * Default US-listed quote sources for 18 in-game markets. The five non-equity
     * instruments use explicitly labelled US-listed proxies because Finnhub's
     * /quote endpoint is documented for US stocks, not native FX/crypto/index quotes.
     * Scales were calibrated from public market snapshots on 2026-10-29 and are approximate.
     */
    private static final String DEFAULT_MAPPINGS_JSON = """
[
    {
      "ticker": "GLD",
      "itemId": "stockmarket:share_xau",
      "scale": 10.918
    },
    {
      "ticker": "QQQ",
      "itemId": "stockmarket:share_nas100",
      "scale": 41.232
    },
    {
      "ticker": "SPY",
      "itemId": "stockmarket:share_sp500",
      "scale": 10.055
    },
    {
      "ticker": "FXE",
      "itemId": "stockmarket:share_eurusd",
      "scale": 0.010815
    },
    {
      "ticker": "IBIT",
      "itemId": "stockmarket:share_btc",
      "scale": 1762.2
    },
    {
      "ticker": "NVDA",
      "itemId": "stockmarket:share_nvda",
      "scale": 1
    },
    {
      "ticker": "GOOGL",
      "itemId": "stockmarket:share_googl",
      "scale": 1
    },
    {
      "ticker": "GOOG",
      "itemId": "stockmarket:share_goog",
      "scale": 1
    },
    {
      "ticker": "AAPL",
      "itemId": "stockmarket:share_aapl",
      "scale": 1
    },
    {
      "ticker": "MSFT",
      "itemId": "stockmarket:share_msft",
      "scale": 1
    },
    {
      "ticker": "JPM",
      "itemId": "stockmarket:share_jpm",
      "scale": 1
    },
    {
      "ticker": "XOM",
      "itemId": "stockmarket:share_xom",
      "scale": 1
    },
    {
      "ticker": "LLY",
      "itemId": "stockmarket:share_lly",
      "scale": 1
    },
    {
      "ticker": "WMT",
      "itemId": "stockmarket:share_wmt",
      "scale": 1
    },
    {
      "ticker": "CAT",
      "itemId": "stockmarket:share_cat",
      "scale": 1
    },
    {
      "ticker": "V",
      "itemId": "stockmarket:share_v",
      "scale": 1
    },
    {
      "ticker": "DIS",
      "itemId": "stockmarket:share_dis",
      "scale": 1
    },
    {
      "ticker": "TSLA",
      "itemId": "stockmarket:share_tsla",
      "scale": 1
    }
  ]
""";

    private final MinecraftServer server;
    private final ServerMarketManager manager;
    private final Path configPath;
    private final HttpClient http;
    private final QuoteHttpTransport httpTransport;
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
    private volatile ItemID usdAnchorItemID;
    private boolean warnedUsdOwnershipConflict;

    private RealStockPriceFeed(MinecraftServer server, ServerMarketManager manager, Path configPath) {
        this(server, manager, configPath, null);
    }

    /**
     * Injectable transport constructor for deterministic feed tests. Production callers
     * use the default HTTPS transport; tests can provide canned HTTP responses without
     * reaching Finnhub.
     */
    RealStockPriceFeed(MinecraftServer server, ServerMarketManager manager, Path configPath,
                       QuoteHttpTransport testTransport) {
        this.server = server;
        this.manager = manager;
        this.configPath = configPath;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.httpTransport = testTransport != null ? testTransport : this::sendHttpRequest;
        loadConfig();
    }

    private HttpResponseData sendHttpRequest(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResponseData(response.statusCode(), response.body());
    }

    @FunctionalInterface
    interface QuoteHttpTransport {
        HttpResponseData send(HttpRequest request) throws Exception;
    }

    record HttpResponseData(int statusCode, String body) {}

    public static RealStockPriceFeed start(MinecraftServer server, ServerMarketManager manager, Path configPath) {
        RealStockPriceFeed feed = new RealStockPriceFeed(server, manager, configPath);
        // USD is an internal quote-unit market, not a Finnhub symbol. Create and
        // anchor it even when the external API token is absent.
        feed.ensureUsdMarketAnchored();
        feed.nextRefreshAt = System.currentTimeMillis() + feed.effectiveRefreshIntervalMs();
        feed.refreshNow();
        return feed;
    }

    /** Called on the server thread. HTTPS requests are never performed in a tick. */
    public void tick() {
        if (closed.get()) return;
        // The base currency is always USD and must remain at exactly 1.0 in the
        // same display currency as all imported reference prices. Maintain it
        // independently of Finnhub credentials and refresh state.
        ensureUsdMarketAnchored();
        if (!enabled || mappings.isEmpty() || !hasUsableApiKey()) return;
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
                    performRefreshBatch();
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

    /**
     * Processes one sequential request batch. Package-private so tests can execute
     * the same production loop synchronously with a fake HTTP transport.
     */
    void performRefreshBatch() throws InterruptedException {
        if (closed.get() || !enabled || mappings.isEmpty() || !hasUsableApiKey()) return;
        long backoffUntil = rateLimitBackoffUntil();
        if (System.currentTimeMillis() < backoffUntil) return;

        for (int i = 0; i < mappings.size(); i++) {
            if (closed.get() || Thread.currentThread().isInterrupted()) return;
            if (System.currentTimeMillis() < rateLimitBackoffUntil()) return;

            Mapping mapping = mappings.get(i);
            FetchResult result = fetchCandle(mapping.ticker);
            if (result.rateLimited()) {
                // Stop the batch immediately. Do not hit the next symbol.
                nextRefreshAt = rateLimitBackoffUntil();
                return;
            }
            if (closed.get()) return;
            if (result.candle() != null) {
                Candle candle = result.candle();
                try {
                    // The worker only obtains data. All game state is read/written
                    // from this task on the server thread, and stale tasks no-op.
                    server.execute(() -> {
                        if (!closed.get()) applyCandle(mapping, candle);
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
    }

    FetchResult fetchCandle(String ticker) {
        try {
            String symbol = URLEncoder.encode(ticker, StandardCharsets.UTF_8);
            String key = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            long nowSeconds = System.currentTimeMillis() / 1000L;
            long fromSeconds = nowSeconds - 5 * 60L;
            URI uri = URI.create("https://finnhub.io/api/v1/stock/candle?symbol=" + symbol
                    + "&resolution=1&from=" + fromSeconds + "&to=" + nowSeconds + "&token=" + key);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponseData response = httpTransport.send(request);
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
                StockMarketMod.LOGGER.warn("[RealStockFeed] Invalid candle response for {}", ticker);
                return FetchResult.failure();
            }
            JsonObject json = parsed.getAsJsonObject();
            if (json.has("error")) {
                String message = json.get("error").getAsString();
                if (isRateLimitFailure("", message)) {
                    recordRateLimit(ticker, "Finnhub API error: " + message);
                    return FetchResult.rateLimitedResult();
                }
                StockMarketMod.LOGGER.warn("[RealStockFeed] No candle data for {}: {}", ticker, message);
                return FetchResult.failure();
            }
            String status = json.has("s") ? json.get("s").getAsString() : "";
            if (!"ok".equalsIgnoreCase(status)
                    || !json.has("t") || !json.has("o") || !json.has("h")
                    || !json.has("l") || !json.has("c")) {
                StockMarketMod.LOGGER.debug("[RealStockFeed] No completed candle payload for {} (status {}).",
                        ticker, status);
                return FetchResult.failure();
            }

            JsonArray timestamps = json.getAsJsonArray("t");
            JsonArray opens = json.getAsJsonArray("o");
            JsonArray highs = json.getAsJsonArray("h");
            JsonArray lows = json.getAsJsonArray("l");
            JsonArray closes = json.getAsJsonArray("c");
            int size = Math.min(timestamps.size(),
                    Math.min(opens.size(), Math.min(highs.size(), Math.min(lows.size(), closes.size()))));

            Candle newestCompleted = null;
            for (int i = 0; i < size; i++) {
                long candleStartSeconds = timestamps.get(i).getAsLong();
                // Finnhub also returns the currently forming minute. Never use it:
                // it is not a stable target and would move the target during replay.
                if (candleStartSeconds <= 0 || candleStartSeconds + 60L > nowSeconds) continue;

                double open = opens.get(i).getAsDouble();
                double high = highs.get(i).getAsDouble();
                double low = lows.get(i).getAsDouble();
                double close = closes.get(i).getAsDouble();
                if (!Double.isFinite(open) || !Double.isFinite(high)
                        || !Double.isFinite(low) || !Double.isFinite(close)
                        || open <= 0 || high <= 0 || low <= 0 || close <= 0
                        || high < Math.max(open, close) || low > Math.min(open, close)) {
                    continue;
                }

                Candle candidate = new Candle(open, high, low, close, candleStartSeconds * 1000L);
                if (newestCompleted == null || candidate.timestampMs() > newestCompleted.timestampMs()) {
                    newestCompleted = candidate;
                }
            }
            if (newestCompleted == null) {
                StockMarketMod.LOGGER.debug("[RealStockFeed] No completed one-minute candle available for {}.", ticker);
                return FetchResult.failure();
            }
            return FetchResult.success(newestCompleted);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.failure();
        } catch (Exception e) {
            if (!closed.get()) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Could not fetch candle for {}: {}", ticker, e.getMessage());
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

    private void applyCandle(Mapping mapping, Candle candle) {
        if (closed.get() || candle.timestampMs() <= mapping.lastUpdated) return;
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
                // External prices own this market's movement. Create it with the
                // plugin-auto-subscribe opt-out set BEFORE the manager's subscribe pass.
                var created = manager.createMarketWithConfig(id,
                        new MarketConfig(true, (float) (candle.open() * mapping.scale), 10f, true));
                if (!(created instanceof ServerMarket candidate)) return;
                if (!candidate.claimExternalPriceFeedOwnership(mapping.ticker)) return;
                stockMarket = candidate;
                mapping.pluginsDetached = true; // no plugins were subscribed on creation
            }

            // A legacy feed-owned market might already have plugin-driven volatility.
            // Detach those subscriptions once; thereafter only the provider-candle
            // replay controls the market price.
            stockMarket.getSettings().ignorePluginAutosubscribe = true;
            if (!mapping.pluginsDetached && SERVER_PLUGIN_MANAGER_AVAILABLE()) {
                var pluginManager = StockMarketModBackend.getServerInstancesPluginManager();
                if (pluginManager != null) {
                    pluginManager.removeCache(id);
                    mapping.pluginsDetached = true;
                }
            }

            long rawOpen = toRawPrice(candle.open() * mapping.scale);
            long rawHigh = toRawPrice(candle.high() * mapping.scale);
            long rawLow = toRawPrice(candle.low() * mapping.scale);
            long rawClose = toRawPrice(candle.close() * mapping.scale);
            rawHigh = Math.max(rawHigh, Math.max(rawOpen, rawClose));
            rawLow = Math.min(rawLow, Math.min(rawOpen, rawClose));

            if (stockMarket.setExternalCandle(mapping.ticker, rawOpen, rawHigh, rawLow,
                    rawClose, candle.timestampMs(), CANDLE_DURATION_MS)) {
                mapping.lastPrice = candle.close();
                mapping.lastUpdated = candle.timestampMs();
            }
        } catch (Exception e) {
            if (!closed.get()) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Failed applying candle for {}: {}",
                        mapping.ticker, e.getMessage());
            }
        }
    }

    private static long toRawPrice(double price) {
        if (!Double.isFinite(price) || price <= 0) return 1L;
        return Math.max(1L, MarketManager.convertToRawAmountStatic(price));
    }

    /**
     * Creates the internal USD quote-unit market and keeps its reference price at
     * exactly 1.0. It is deliberately not a Finnhub mapping: FX, crypto and stocks
     * all use USD as their shared quote currency in the pair selector.
     *
     * <p>The market's ownership marker prevents an unrelated market using the same
     * item ID from being silently taken over. A created USD market opts out of
     * plugin auto-subscription so a target-price or volatility plugin cannot move
     * the quote unit away from 1.0.
     */
    private void ensureUsdMarketAnchored() {
        if (closed.get() || manager == null) return;
        try {
            ItemID itemID = usdAnchorItemID;
            if (itemID == null) {
                ItemStack usdStack = new ItemStack(StockMarketItems.SHARE_USD.get());
                itemID = ItemID.getOrRegisterFromItemStackServerSide_direct(usdStack);
                if (itemID == null || !itemID.isValid()) {
                    StockMarketMod.LOGGER.warn("[RealStockFeed] Could not register the USD quote-unit item.");
                    return;
                }
                usdAnchorItemID = itemID;
            }

            var existing = manager.getMarket(itemID);
            ServerMarket usdMarket;
            if (existing == null) {
                var created = manager.createMarketWithConfig(
                        itemID,
                        new MarketConfig(true, 1.0f, 10.0f, true));
                if (!(created instanceof ServerMarket candidate)) {
                    StockMarketMod.LOGGER.warn("[RealStockFeed] Could not create the USD quote-unit market.");
                    return;
                }
                if (!candidate.claimExternalPriceFeedOwnership("USD")) {
                    StockMarketMod.LOGGER.warn("[RealStockFeed] Could not claim the newly created USD quote-unit market.");
                    return;
                }
                usdMarket = candidate;
                StockMarketMod.LOGGER.info("[RealStockFeed] Created the USD quote-unit market at 1.0.");
            } else if (existing instanceof ServerMarket candidate
                    && candidate.isOwnedByExternalPriceFeed("USD")) {
                usdMarket = candidate;
            } else {
                if (!warnedUsdOwnershipConflict) {
                    StockMarketMod.LOGGER.warn("[RealStockFeed] The USD quote-unit item already has an unrelated market; refusing to take it over. Remove that market before using fixed-USD pair mode.");
                    warnedUsdOwnershipConflict = true;
                }
                return;
            }

            long rawOneUsd = Math.max(1L, MarketManager.convertToRawAmountStatic(1.0));
            usdMarket.setExternalReferencePrice("USD", rawOneUsd);
        } catch (Exception e) {
            if (!closed.get()) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Could not maintain the USD quote-unit market: {}", e.getMessage());
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
                String defaultMappings = DEFAULT_MAPPINGS_JSON.strip();
                String example = """
                        {
                          "enabled": true,
                          "apiKey": "REPLACE_WITH_YOUR_FINNHUB_API_TOKEN",
                          "refreshSeconds": 60,
                          "useDefaultMappings": true,
                          "mappings": %s
                        }
                        """.formatted(defaultMappings);
                Files.writeString(configPath, example, StandardCharsets.UTF_8);
                StockMarketMod.LOGGER.info("[RealStockFeed] Created configuration with 18 default market mappings at {}. The feed remains inactive until you add a Finnhub API token.", configPath);
            }

            JsonObject root = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8)).getAsJsonObject();
            enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
            apiKey = root.has("apiKey") ? root.get("apiKey").getAsString().trim() : "";
            refreshMs = Math.max(MIN_REFRESH_MS,
                    root.has("refreshSeconds") ? Math.multiplyExact(root.get("refreshSeconds").getAsLong(), 1000L) : MIN_REFRESH_MS);

            mappings.clear();
            Set<String> mappedItemIds = new HashSet<>();
            Set<String> mappedTickers = new HashSet<>();
            JsonArray configuredMappings = root.has("mappings") && root.get("mappings").isJsonArray()
                    ? root.getAsJsonArray("mappings") : new JsonArray();
            boolean useDefaultMappings = !root.has("useDefaultMappings")
                    || root.get("useDefaultMappings").getAsBoolean();
            if (configuredMappings.isEmpty() && useDefaultMappings) {
                configuredMappings = JsonParser.parseString(DEFAULT_MAPPINGS_JSON).getAsJsonArray();
                root.add("mappings", configuredMappings.deepCopy());
                root.addProperty("useDefaultMappings", true);
                Files.writeString(configPath,
                        new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator(),
                        StandardCharsets.UTF_8);
                StockMarketMod.LOGGER.info("[RealStockFeed] Added the default 18-market mapping set to {}. Set useDefaultMappings=false to keep mappings empty.", configPath);
            }
            configuredMappings.forEach(element -> {
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
        boolean pluginsDetached;

        Mapping(String ticker, String itemId, double scale) {
            this.ticker = ticker;
            this.itemId = itemId;
            this.scale = scale;
        }
    }

    record Candle(double open, double high, double low, double close, long timestampMs) {}

    record FetchResult(Candle candle, boolean rateLimited) {
        static FetchResult success(Candle candle) {
            return new FetchResult(candle, false);
        }
        static FetchResult failure() {
            return new FetchResult(null, false);
        }
        static FetchResult rateLimitedResult() {
            return new FetchResult(null, true);
        }
    }
}
