package net.kroia.stockmarket.util;

import net.kroia.banksystem.util.ItemID;
import net.kroia.stockmarket.data.table.record.MarketPriceStruct;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PriceHistoryDataLiveFallbackTest {

    @Test
    void liveOnlyFallbackContainsCurrentOhlcCandleAndNoOlderDataMarker() {
        ItemID itemID = new ItemID((short) 42);
        long timestamp = 1_700_000_000_000L;
        MarketPriceStruct current = new MarketPriceStruct(
                (short) 42, 1_250L, 1_200L, 1_300L, timestamp, 7.0f);

        PriceHistoryData data = PriceHistoryData.fromLiveMarketCandle(itemID, 100, current, 1_280L);

        assertEquals(1, data.getCandles().size());
        PriceHistoryData.Candle candle = data.getCandles().getFirst();
        assertEquals(timestamp, candle.openTimestamp);
        assertEquals(1_250L, candle.open);
        assertEquals(1_300L, candle.high);
        assertEquals(1_200L, candle.low);
        assertEquals(7.0f, candle.tradedVolume);
        assertEquals(1_280L, data.getCurrentMarketPrice());
        assertEquals(PriceHistoryData.NO_OLDER_DATA, data.getOldestAvailableTimestamp());
        assertFalse(data.isTruncated());
    }

    @Test
    void missingLiveCandleStillCreatesSingleCandleAtCurrentPrice() {
        ItemID itemID = new ItemID((short) 43);

        PriceHistoryData data = PriceHistoryData.fromLiveMarketCandle(itemID, 100, null, 975L);

        assertEquals(1, data.getCandles().size());
        PriceHistoryData.Candle candle = data.getCandles().getFirst();
        assertEquals(975L, candle.open);
        assertEquals(975L, candle.high);
        assertEquals(975L, candle.low);
        assertEquals(975L, data.getCurrentMarketPrice());
    }

    @Test
    void invalidOhlcUsesCurrentPriceToAvoidZeroPriceChart() {
        ItemID itemID = new ItemID((short) 44);
        MarketPriceStruct invalid = new MarketPriceStruct(
                (short) 44, 0L, 0L, 0L, 1L, 0.0f);

        PriceHistoryData data = PriceHistoryData.fromLiveMarketCandle(itemID, 100, invalid, 815L);

        PriceHistoryData.Candle candle = data.getCandles().getFirst();
        assertEquals(815L, candle.open);
        assertEquals(815L, candle.high);
        assertEquals(815L, candle.low);
        assertEquals(815L, data.getCurrentMarketPrice());
    }
}
