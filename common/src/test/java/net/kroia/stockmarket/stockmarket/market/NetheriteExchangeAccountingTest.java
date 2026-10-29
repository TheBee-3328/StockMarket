package net.kroia.stockmarket.stockmarket.market;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NetheriteExchangeAccountingTest {
    private static final long SCALE = 1_000L;

    @Test
    void withdrawalReducesRemainingNetheritePrincipalAndDepositRatio() {
        NetheriteExchangeAccounting accounting = new NetheriteExchangeAccounting();
        UUID player = UUID.randomUUID();
        accounting.recordDeposit(player, 500, 2, true, SCALE);
        accounting.recordDeposit(player, 100, 10, false, SCALE);

        assertEquals(2, accounting.getMaxNetheriteWithdrawals(player, SCALE));
        assertTrue(accounting.withdrawNetherite(player, 1, SCALE));

        assertEquals(500 * SCALE, accounting.getNetheritePrincipalUsdRaw(player));
        assertEquals(1_500 * SCALE, accounting.getTotalDepositedUsdRaw(player));
        assertEquals(1, accounting.getMaxNetheriteWithdrawals(player, SCALE));
    }

    @Test
    void realizedStockProfitOnlyUnlocksTheNetheriteWeightedShare() {
        NetheriteExchangeAccounting accounting = new NetheriteExchangeAccounting();
        UUID player = UUID.randomUUID();
        accounting.recordDeposit(player, 500, 1, true, SCALE);
        accounting.recordDeposit(player, 100, 5, false, SCALE);

        // One hundred shares bought at an effective $101 per share (including fee),
        // then sold at $130. The $2,900 realized profit is net of the buy cost/fee.
        accounting.recordTrade(player, "example:aapl", 100 * SCALE, 101 * SCALE, SCALE);
        accounting.recordTrade(player, "example:aapl", -100 * SCALE, 130 * SCALE, SCALE);

        assertEquals(2_900 * SCALE, accounting.getRealizedProfitUsdRaw(player));
        // Netherite is 50% of the $1,000 deposit, so $1,450 of profit is eligible;
        // that funds two extra $500 ingots. The one deposited ingot remains principal.
        assertEquals(3, accounting.getMaxNetheriteWithdrawals(player, SCALE));
    }

    @Test
    void principalCanStillBeWithdrawnAfterNetTradingLosses() {
        NetheriteExchangeAccounting accounting = new NetheriteExchangeAccounting();
        UUID player = UUID.randomUUID();
        accounting.recordDeposit(player, 500, 1, true, SCALE);
        accounting.recordDeposit(player, 100, 5, false, SCALE);
        accounting.recordTrade(player, "example:aapl", 10 * SCALE, 110 * SCALE, SCALE);
        accounting.recordTrade(player, "example:aapl", -10 * SCALE, 90 * SCALE, SCALE);

        assertTrue(accounting.getRealizedProfitUsdRaw(player) < 0);
        assertEquals(1, accounting.getMaxNetheriteWithdrawals(player, SCALE));
        assertTrue(accounting.withdrawNetherite(player, 1, SCALE));
        assertEquals(0, accounting.getNetheritePrincipalUsdRaw(player));
    }

    @Test
    void untrackedOrGiftedSharesDoNotGenerateProfitCredit() {
        NetheriteExchangeAccounting accounting = new NetheriteExchangeAccounting();
        UUID player = UUID.randomUUID();
        accounting.recordDeposit(player, 500, 1, true, SCALE);
        accounting.recordDeposit(player, 100, 5, false, SCALE);

        assertFalse(accounting.recordTrade(player, "example:msft", -10 * SCALE, 150 * SCALE, SCALE));
        assertEquals(0, accounting.getRealizedProfitUsdRaw(player));
        assertEquals(1, accounting.getMaxNetheriteWithdrawals(player, SCALE));
    }

    @Test
    void profitWithdrawalDoesNotConsumePrincipalAndCannotBeRepeatedWithoutProfit() {
        NetheriteExchangeAccounting accounting = new NetheriteExchangeAccounting();
        UUID player = UUID.randomUUID();
        accounting.recordDeposit(player, 500, 1, true, SCALE);
        accounting.recordDeposit(player, 100, 5, false, SCALE);
        accounting.recordTrade(player, "example:stock", 100 * SCALE, 101 * SCALE, SCALE);
        accounting.recordTrade(player, "example:stock", -100 * SCALE, 130 * SCALE, SCALE);

        assertTrue(accounting.withdrawNetherite(player, 1, SCALE));
        assertEquals(500 * SCALE, accounting.getNetheritePrincipalUsdRaw(player));
        assertEquals(2_900 * SCALE, accounting.getRealizedProfitUsdRaw(player));
        assertEquals(950 * SCALE, accounting.getAvailableProfitAllowanceRaw(player));
        assertEquals(2, accounting.getMaxNetheriteWithdrawals(player, SCALE));
    }

    @Test
    void savedLedgerRoundTripsDepositPositionsAndRealizedProfit() {
        NetheriteExchangeAccounting accounting = new NetheriteExchangeAccounting();
        UUID player = UUID.randomUUID();
        accounting.recordDeposit(player, 500, 1, true, SCALE);
        accounting.recordDeposit(player, 100, 5, false, SCALE);
        accounting.recordTrade(player, "example:nvda", 10 * SCALE, 110 * SCALE, SCALE);

        NetheriteExchangeAccounting restored = NetheriteExchangeAccounting.load(accounting.save());

        assertEquals(accounting.getTotalDepositedUsdRaw(player), restored.getTotalDepositedUsdRaw(player));
        assertEquals(accounting.getNetheritePrincipalUsdRaw(player), restored.getNetheritePrincipalUsdRaw(player));
        assertEquals(0, restored.getRealizedProfitUsdRaw(player));
        assertTrue(restored.recordTrade(player, "example:nvda", -10 * SCALE, 125 * SCALE, SCALE));
        assertEquals(150 * SCALE, restored.getRealizedProfitUsdRaw(player));
    }
}
