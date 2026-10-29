package net.kroia.stockmarket.util;

/**
 * Pure-Java normalization for the initial candle returned when a market has no
 * persisted history yet. Kept free of Minecraft and mod types so this logic can
 * be unit-tested in a regular JVM.
 */
public final class LiveCandleFallback {
    private LiveCandleFallback() {
    }

    public record Seed(
            long timestamp,
            long open,
            long high,
            long low,
            float volume,
            long currentPrice) {
    }

    public static Seed normalize(
            boolean hasLiveCandle,
            long liveOpen,
            long liveLow,
            long liveHigh,
            long liveTimestamp,
            float liveVolume,
            long currentMarketPrice,
            long fallbackTimestamp) {
        long current = Math.max(0L, currentMarketPrice);
        if (current <= 0L && hasLiveCandle) {
            current = Math.max(liveOpen, Math.max(liveHigh, liveLow));
        }

        long open = hasLiveCandle && liveOpen > 0L ? liveOpen : current;
        long rawHigh = hasLiveCandle && liveHigh > 0L
                ? liveHigh
                : Math.max(open, current);
        long rawLow = hasLiveCandle && liveLow > 0L
                ? liveLow
                : Math.min(open, current);
        long high = Math.max(current, Math.max(open, rawHigh));
        long low = Math.min(current, Math.min(open, rawLow));
        long timestamp = hasLiveCandle && liveTimestamp > 0L
                ? liveTimestamp
                : fallbackTimestamp;
        float volume = hasLiveCandle ? Math.max(0.0f, liveVolume) : 0.0f;

        return new Seed(timestamp, open, high, low, volume, current);
    }
}
