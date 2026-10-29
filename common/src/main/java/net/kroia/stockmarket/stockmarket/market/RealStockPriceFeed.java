package net.kroia.stockmarket.stockmarket.market;

import net.kroia.banksystem.util.ItemID;
import net.kroia.stockmarket.StockMarketMod;
import net.kroia.stockmarket.stockmarket.marketmanager.ServerMarketManager;
import net.minecraft.server.MinecraftServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional Twelve Data quote importer for the master server.
 *
 * <p>Each external ticker is represented by a Minecraft item market only when a
 * server admin explicitly maps it in stockmarket/real-stocks.json. This prevents
 * the feed from hijacking vanilla item prices. All network I/O runs on a single
 * daemon thread and price application is scheduled back onto the server thread.
 */
public final class RealStockPriceFeed implements AutoCloseable {
    private static final long MIN_REFRESH_MS = 60_000L;
    private static final long RATE_LIMIT_BACKOFF_MS = 5 * 60_000L;
    private final MinecraftServer server;
    private final ServerMarketManager manager;
    private final Path configPath;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "StockMarket-RealStockFeed");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final Map<String, Mapping> mappings = new LinkedHashMap<>();
    private volatile String apiKey = "";
    private volatile boolean enabled = true;
    private volatile long refreshMs = MIN_REFRESH_MS;
    private long nextRefreshAt;
    private long lastRateLimitAt;

    private RealStockPriceFeed(MinecraftServer server, ServerMarketManager manager, Path configPath) {
        this.server = server;
        this.manager = manager;
        this.configPath = configPath;
        loadConfig();
    }

    public static RealStockPriceFeed start(MinecraftServer server, ServerMarketManager manager, Path configPath) {
        RealStockPriceFeed feed = new RealStockPriceFeed(server, manager, configPath);
        feed.refreshNow();
        return feed;
    }

    /** Called from the server tick thread. Actual HTTPS requests are never made here. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (!enabled || now < nextRefreshAt || inFlight.get()) return;
        nextRefreshAt = now + Math.max(MIN_REFRESH_MS, refreshMs);
        refreshNow();
    }

    private void refreshNow() {
        if (!enabled || apiKey.isBlank() || mappings.isEmpty() || !inFlight.compareAndSet(false, true)) return;
        worker.submit(() -> {
            try {
                long now = System.currentTimeMillis();
                if (now - lastRateLimitAt < RATE_LIMIT_BACKOFF_MS) return;
                for (Mapping mapping : mappings.values()) {
                    if (Thread.currentThread().isInterrupted()) return;
                    Quote quote = fetchQuote(mapping.ticker);
                    if (quote == null) continue;
                    server.execute(() -> applyQuote(mapping, quote));
                    // A free tier may be credit-limited. Conservative pacing avoids
                    // a burst of requests when several symbols are configured.
                    if (mappings.size() > 1) Thread.sleep(8_000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Refresh failed; retaining last known prices: {}", e.getMessage());
            } finally {
                inFlight.set(false);
            }
        });
    }

    private Quote fetchQuote(String ticker) {
        try {
            String symbol = URLEncoder.encode(ticker, StandardCharsets.UTF_8);
            String key = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            URI uri = URI.create("https://api.twelvedata.com/price?symbol=" + symbol + "&apikey=" + key);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 429) {
                lastRateLimitAt = System.currentTimeMillis();
                StockMarketMod.LOGGER.warn("[RealStockFeed] Provider rate limit reached; delaying requests.");
                return null;
            }
            if (response.statusCode() / 100 != 2) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Provider HTTP status {} for {}", response.statusCode(), ticker);
                return null;
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("code") || !json.has("price")) {
                String message = json.has("message") ? json.get("message").getAsString() : "unexpected response";
                StockMarketMod.LOGGER.warn("[RealStockFeed] No quote for {}: {}", ticker, message);
                return null;
            }
            double price = Double.parseDouble(json.get("price").getAsString());
            if (!Double.isFinite(price) || price <= 0) return null;
            return new Quote(price, System.currentTimeMillis());
        } catch (Exception e) {
            StockMarketMod.LOGGER.warn("[RealStockFeed] Could not fetch {}: {}", ticker, e.getMessage());
            return null;
        }
    }

    private void applyQuote(Mapping mapping, Quote quote) {
        try {
            net.minecraft.world.item.ItemStack stack = resolveItemStack(mapping.itemId);
            if (stack.isEmpty()) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Could not resolve item '{}' for {}", mapping.itemId, mapping.ticker);
                return;
            }
            ItemID id = ItemID.getOrRegisterFromItemStackServerSide_direct(stack);
            // Unknown/unresolvable mapped items are intentionally skipped.
            if (id == null || !id.isValid()) {
                StockMarketMod.LOGGER.warn("[RealStockFeed] Invalid Minecraft item mapping '{}' for {}", mapping.itemId, mapping.ticker);
                return;
            }
            var market = manager.getMarket(id);
            if (market == null) {
                market = manager.createMarket(id);
            }
            if (market instanceof ServerMarket serverMarket) {
                long raw = Math.max(1L, net.kroia.stockmarket.stockmarket.marketmanager.MarketManager.convertToRawAmountStatic(quote.price * mapping.scale));
                serverMarket.setExternalReferencePrice(raw);
                mapping.lastPrice = quote.price;
                mapping.lastUpdated = quote.timestamp;
            }
        } catch (Exception e) {
            StockMarketMod.LOGGER.warn("[RealStockFeed] Failed applying {}: {}", mapping.ticker, e.getMessage());
        }
    }

    private static net.minecraft.world.item.ItemStack resolveItemStack(String itemId) {
        try {
            net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.parse(itemId);
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(location);
            return item == net.minecraft.world.item.Items.AIR
                    ? net.minecraft.world.item.ItemStack.EMPTY
                    : new net.minecraft.world.item.ItemStack(item);
        } catch (Exception e) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }

    private void loadConfig() {
        try {
            Files.createDirectories(configPath.getParent());
            if (!Files.exists(configPath)) {
                String example = """
                        {
                          "enabled": true,
                          "apiKey": "PUT_YOUR_TWELVE_DATA_API_KEY_HERE",
                          "refreshSeconds": 60,
                          "mappings": [
                            { "ticker": "AAPL", "itemId": "minecraft:diamond", "scale": 1.0 },
                            { "ticker": "MSFT", "itemId": "minecraft:emerald", "scale": 1.0 },
                            { "ticker": "NVDA", "itemId": "minecraft:gold_ingot", "scale": 1.0 },
                            { "ticker": "TSLA", "itemId": "minecraft:iron_ingot", "scale": 1.0 },
                            { "ticker": "GOOGL", "itemId": "minecraft:lapis_lazuli", "scale": 1.0 }
                          ]
                        }
                        """;
                Files.writeString(configPath, example, StandardCharsets.UTF_8);
                StockMarketMod.LOGGER.info("[RealStockFeed] Created configuration at {}", configPath);
            }
            JsonObject root = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8)).getAsJsonObject();
            enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
            apiKey = root.has("apiKey") ? root.get("apiKey").getAsString().trim() : "";
            refreshMs = Math.max(MIN_REFRESH_MS, root.has("refreshSeconds") ? root.get("refreshSeconds").getAsLong() * 1000L : MIN_REFRESH_MS);
            mappings.clear();
            if (root.has("mappings") && root.get("mappings").isJsonArray()) {
                root.getAsJsonArray("mappings").forEach(element -> {
                    if (!element.isJsonObject()) return;
                    JsonObject obj = element.getAsJsonObject();
                    String ticker = obj.has("ticker") ? obj.get("ticker").getAsString().trim().toUpperCase(Locale.ROOT) : "";
                    String itemId = obj.has("itemId") ? obj.get("itemId").getAsString().trim() : "";
                    double scale = obj.has("scale") ? obj.get("scale").getAsDouble() : 1.0;
                    if (!ticker.matches("[A-Z0-9.]{1,15}") || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !Double.isFinite(scale) || scale <= 0) return;
                    mappings.put(ticker, new Mapping(ticker, itemId, scale));
                });
            }
        } catch (Exception e) {
            enabled = false;
            StockMarketMod.LOGGER.error("[RealStockFeed] Could not load {}. Feed disabled: {}", configPath, e.getMessage());
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    private static final class Mapping {
        final String ticker;
        final String itemId;
        final double scale;
        volatile double lastPrice;
        volatile long lastUpdated;
        Mapping(String ticker, String itemId, double scale) {
            this.ticker = ticker; this.itemId = itemId; this.scale = scale;
        }
    }

    private record Quote(double price, long timestamp) {}
}
