package net.kroia.stockmarket.stockmarket.market;

/**
 * Configuration contract for the optional real-stock importer.
 *
 * <p>The server creates {@code config/StockMarket/real-stocks.json} on first
 * startup. Set {@code apiKey} to a Finnhub API token, keep the refresh at 60
 * seconds or longer, and add explicit ticker-to-dedicated-share-item mappings.
 *
 * <p>The generated mappings list is empty and the token is an inert placeholder.
 * Existing markets are never adopted by the feed unless they carry the matching
 * persisted feed-ownership marker. Vanilla {@code minecraft:} item mappings are
 * rejected to protect normal simulated item markets.
 *
 * <p>Finnhub's personal-use plan prohibits redistribution or sharing of market
 * data and derived results without written approval. Obtain approval before
 * displaying Finnhub-derived prices to other players on a multiplayer server.
 */
public final class RealStockFeedConfig {
    private RealStockFeedConfig() {}
}
