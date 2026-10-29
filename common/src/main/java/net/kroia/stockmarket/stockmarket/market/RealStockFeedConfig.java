package net.kroia.stockmarket.stockmarket.market;

/**
 * Configuration contract for the optional real-stock importer.
 *
 * <p>The server creates {@code world/data/StockMarket/real-stocks.json} on
 * first startup. Set {@code apiKey} to a Twelve Data API key, keep the refresh
 * at 60 seconds for the free-tier-safe default, and add ticker-to-item mappings.
 *
 * <p>Existing Minecraft item markets retain the normal simulated pricing when
 * they are not explicitly listed in the file. The importer never changes any
 * markets by default without the administrator reviewing the generated file.
 */
public final class RealStockFeedConfig {
    private RealStockFeedConfig() {}
}
