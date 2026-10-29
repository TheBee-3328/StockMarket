package net.kroia.stockmarket.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PriceHistoryDataLiveFallbackTest {

    @Test
    void liveCandlePreservesOhlcTimestampVolumeAndCurrentPrice() {
        long timestamp = 1_700_000_000_000L;

        LiveCandleFallback.Seed seed = LiveCandleFallback.normalize(
                true, 1_250L, 1_200L, 1_300L, timestamp, 7.0f, 1_280L, 99L);

        assertEquals(timestamp, seed.timestamp());
        assertEquals(1_250L, seed.open());
        assertEquals(1_300L, seed.high());
        assertEquals(1_200L, seed.low());
        assertEquals(7.0f, seed.volume());
        assertEquals(1_280L, seed.currentPrice());
    }

    @Test
    void missingLiveCandleCreatesFlatCandleAtCurrentPrice() {
        LiveCandleFallback.Seed seed = LiveCandleFallback.normalize(
                false, 0L, 0L, 0L, 0L, 0.0f, 975L, 1_700_000_000_000L);

        assertEquals(1_700_000_000_000L, seed.timestamp());
        assertEquals(975L, seed.open());
        assertEquals(975L, seed.high());
        assertEquals(975L, seed.low());
        assertEquals(0.0f, seed.volume());
        assertEquals(975L, seed.currentPrice());
    }

    @Test
    void invalidOhlcUsesCurrentPriceAndClampsNegativeVolume() {
        LiveCandleFallback.Seed seed = LiveCandleFallback.normalize(
                true, 0L, 0L, 0L, 1L, -4.0f, 815L, 99L);

        assertEquals(1L, seed.timestamp());
        assertEquals(815L, seed.open());
        assertEquals(815L, seed.high());
        assertEquals(815L, seed.low());
        assertEquals(0.0f, seed.volume());
        assertEquals(815L, seed.currentPrice());
    }

    @Test
    void zeroCurrentPriceFallsBackToLiveOhlc() {
        LiveCandleFallback.Seed seed = LiveCandleFallback.normalize(
                true, 1_250L, 1_200L, 1_300L, 1_700_000_000_000L, 2.0f, 0L, 99L);

        assertEquals(1_300L, seed.currentPrice());
        assertEquals(1_250L, seed.open());
        assertEquals(1_300L, seed.high());
        assertEquals(1_200L, seed.low());
    }
}
