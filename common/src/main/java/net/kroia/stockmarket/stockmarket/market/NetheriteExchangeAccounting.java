package net.kroia.stockmarket.stockmarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pure accounting rules for the per-player USD exchange netherite cap.
 *
 * All money values use BankSystem's raw currency scale. The exchange contributes
 * whole USD amounts multiplied by the scale factor; trade PnL is recorded in the
 * same raw currency units.
 */
public final class NetheriteExchangeAccounting {
    public static final long NETHERITE_USD_VALUE = 500L;
    private static final int BUY_FEE_BASIS_POINTS = 100;
    private static final BigInteger TEN_THOUSAND = BigInteger.valueOf(10_000L);

    private final Map<UUID, PlayerLedger> players = new HashMap<>();

    public synchronized void recordDeposit(UUID playerId, long unitValueUsd, long itemCount,
                                           boolean isNetherite, long scaleFactor) {
        if (playerId == null || unitValueUsd <= 0 || itemCount <= 0 || scaleFactor <= 0) return;
        PlayerLedger player = players.computeIfAbsent(playerId, ignored -> new PlayerLedger());
        long value = multiplySaturated(multiplySaturated(unitValueUsd, itemCount), scaleFactor);
        player.totalDepositedUsdRaw = addSaturated(player.totalDepositedUsdRaw, value);
        if (isNetherite) {
            player.netheritePrincipalUsdRaw = addSaturated(player.netheritePrincipalUsdRaw, value);
        }
    }

    /**
     * Record a completed order on a USD-quoted asset. signedVolume is positive for
     * buys and negative for sells; executionPriceRaw already includes buy fees in
     * Order's transferred-money accounting.
     */
    public synchronized boolean recordTrade(UUID playerId, String assetKey, long signedVolumeRaw,
                                            long executionPriceRaw, long scaleFactor) {
        if (playerId == null || assetKey == null || assetKey.isBlank()
                || signedVolumeRaw == 0 || executionPriceRaw <= 0 || scaleFactor <= 0) {
            return false;
        }

        PlayerLedger player = players.computeIfAbsent(playerId, ignored -> new PlayerLedger());
        Position position = player.positions.get(assetKey);

        if (signedVolumeRaw > 0) {
            if (position == null) {
                position = new Position();
                player.positions.put(assetKey, position);
            }
            long newQuantity = addSaturated(position.quantityRaw, signedVolumeRaw);
            if (newQuantity <= 0) return false;
            BigInteger oldValue = BigInteger.valueOf(position.quantityRaw)
                    .multiply(BigInteger.valueOf(position.averageCostPriceRaw));
            BigInteger newValue = BigInteger.valueOf(signedVolumeRaw)
                    .multiply(BigInteger.valueOf(executionPriceRaw));
            long newAverage = oldValue.add(newValue)
                    .divide(BigInteger.valueOf(newQuantity)).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            position.quantityRaw = newQuantity;
            position.averageCostPriceRaw = newAverage;
            return true;
        }

        if (position == null || position.quantityRaw <= 0) {
            // Shares acquired outside the tracked market history (for example,
            // creative grants or gifts) have no cost basis and generate no profit credit.
            return false;
        }

        long soldQuantityRaw = signedVolumeRaw == Long.MIN_VALUE ? Long.MAX_VALUE : -signedVolumeRaw;
        long matchedQuantityRaw = Math.min(soldQuantityRaw, position.quantityRaw);
        BigInteger deltaPrice = BigInteger.valueOf(executionPriceRaw)
                .subtract(BigInteger.valueOf(position.averageCostPriceRaw));
        BigInteger profitRaw = deltaPrice.multiply(BigInteger.valueOf(matchedQuantityRaw))
                .divide(BigInteger.valueOf(scaleFactor));
        long realizedDelta = profitRaw.max(BigInteger.valueOf(Long.MIN_VALUE))
                .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        player.realizedProfitUsdRaw = addSaturated(player.realizedProfitUsdRaw, realizedDelta);

        position.quantityRaw -= matchedQuantityRaw;
        if (position.quantityRaw <= 0) player.positions.remove(assetKey);
        return true;
    }

    /** Principal-backed allowance plus the netherite-weighted share of net realized profits. */
    public synchronized long getMaxNetheriteWithdrawals(UUID playerId, long scaleFactor) {
        PlayerLedger player = players.get(playerId);
        if (player == null || scaleFactor <= 0 || player.totalDepositedUsdRaw <= 0
                || player.netheritePrincipalUsdRaw <= 0) return 0L;

        long valuePerIngot = multiplySaturated(NETHERITE_USD_VALUE, scaleFactor);
        if (valuePerIngot <= 0) return 0L;

        long principalIngots = player.netheritePrincipalUsdRaw / valuePerIngot;
        long profitAllowanceRaw = getAvailableProfitAllowanceRaw(player);
        long profitIngots = profitAllowanceRaw / valuePerIngot
                .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        return addSaturated(principalIngots, profitIngots);
    }

    /**
     * Books a withdrawal after validating the full requested amount. Profit-funded
     * units are consumed first, then netherite principal. This keeps the result
     * independent of whether the player redeems an allowance in one click or several.
     */
    public synchronized boolean withdrawNetherite(UUID playerId, long count, long scaleFactor) {
        if (playerId == null || count <= 0 || scaleFactor <= 0) return false;
        PlayerLedger player = players.get(playerId);
        if (player == null || count > getMaxNetheriteWithdrawals(playerId, scaleFactor)) return false;

        long valuePerIngot = multiplySaturated(NETHERITE_USD_VALUE, scaleFactor);
        long profitAllowanceRaw = getAvailableProfitAllowanceRaw(player);
        long profitIngotsAvailable = profitAllowanceRaw / valuePerIngot;
        long profitIngotsSpent = Math.min(count, profitIngotsAvailable);
        long principalIngotsSpent = count - profitIngotsSpent;

        long profitSpendRaw = multiplySaturated(profitIngotsSpent, valuePerIngot);
        long principalSpendRaw = multiplySaturated(principalIngotsSpent, valuePerIngot);
        if (principalSpendRaw > player.netheritePrincipalUsdRaw
                || principalSpendRaw > player.totalDepositedUsdRaw
                || profitSpendRaw > player.realizedProfitUsdRaw) {
            return false;
        }

        player.netheriteProfitWithdrawnUsdRaw = addSaturated(
                player.netheriteProfitWithdrawnUsdRaw, profitSpendRaw);
        player.netheritePrincipalUsdRaw -= principalSpendRaw;
        player.totalDepositedUsdRaw -= principalSpendRaw;
        if (player.totalDepositedUsdRaw < player.netheritePrincipalUsdRaw) {
            player.totalDepositedUsdRaw = player.netheritePrincipalUsdRaw;
        }
        return true;
    }

    public synchronized long getTotalDepositedUsdRaw(UUID playerId) {
        PlayerLedger player = players.get(playerId);
        return player == null ? 0L : player.totalDepositedUsdRaw;
    }

    public synchronized long getNetheritePrincipalUsdRaw(UUID playerId) {
        PlayerLedger player = players.get(playerId);
        return player == null ? 0L : player.netheritePrincipalUsdRaw;
    }

    public synchronized long getRealizedProfitUsdRaw(UUID playerId) {
        PlayerLedger player = players.get(playerId);
        return player == null ? 0L : player.realizedProfitUsdRaw;
    }

    public synchronized long getAvailableProfitAllowanceRaw(UUID playerId) {
        PlayerLedger player = players.get(playerId);
        return player == null ? 0L : getAvailableProfitAllowanceRaw(player);
    }

    private static long getAvailableProfitAllowanceRaw(PlayerLedger player) {
        if (player.totalDepositedUsdRaw <= 0 || player.netheritePrincipalUsdRaw <= 0) return 0L;
        long netProfitRaw = Math.max(0L, player.realizedProfitUsdRaw);
        BigInteger eligible = BigInteger.valueOf(netProfitRaw)
                .multiply(BigInteger.valueOf(player.netheritePrincipalUsdRaw))
                .divide(BigInteger.valueOf(player.totalDepositedUsdRaw));
        BigInteger remaining = eligible.subtract(BigInteger.valueOf(player.netheriteProfitWithdrawnUsdRaw))
                .max(BigInteger.ZERO);
        return remaining.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    public synchronized CompoundTag save() {
        CompoundTag root = new CompoundTag();
        ListTag playerTags = new ListTag();
        for (Map.Entry<UUID, PlayerLedger> entry : players.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("uuid", entry.getKey());
            PlayerLedger player = entry.getValue();
            playerTag.putLong("totalDepositedUsdRaw", player.totalDepositedUsdRaw);
            playerTag.putLong("netheritePrincipalUsdRaw", player.netheritePrincipalUsdRaw);
            playerTag.putLong("realizedProfitUsdRaw", player.realizedProfitUsdRaw);
            playerTag.putLong("netheriteProfitWithdrawnUsdRaw", player.netheriteProfitWithdrawnUsdRaw);

            ListTag positions = new ListTag();
            for (Map.Entry<String, Position> posEntry : player.positions.entrySet()) {
                Position position = posEntry.getValue();
                if (position.quantityRaw <= 0 || position.averageCostPriceRaw <= 0) continue;
                CompoundTag positionTag = new CompoundTag();
                positionTag.putString("asset", posEntry.getKey());
                positionTag.putLong("quantityRaw", position.quantityRaw);
                positionTag.putLong("averageCostPriceRaw", position.averageCostPriceRaw);
                positions.add(positionTag);
            }
            playerTag.put("positions", positions);
            playerTags.add(playerTag);
        }
        root.put("players", playerTags);
        return root;
    }

    public static NetheriteExchangeAccounting load(CompoundTag root) {
        NetheriteExchangeAccounting accounting = new NetheriteExchangeAccounting();
        ListTag playerTags = root.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < playerTags.size(); i++) {
            CompoundTag playerTag = playerTags.getCompound(i);
            if (!playerTag.hasUUID("uuid")) continue;
            UUID playerId = playerTag.getUUID("uuid");
            PlayerLedger player = new PlayerLedger();
            player.totalDepositedUsdRaw = Math.max(0L, playerTag.getLong("totalDepositedUsdRaw"));
            player.netheritePrincipalUsdRaw = Math.max(0L, playerTag.getLong("netheritePrincipalUsdRaw"));
            player.realizedProfitUsdRaw = playerTag.getLong("realizedProfitUsdRaw");
            player.netheriteProfitWithdrawnUsdRaw = Math.max(0L, playerTag.getLong("netheriteProfitWithdrawnUsdRaw"));
            if (player.netheritePrincipalUsdRaw > player.totalDepositedUsdRaw) {
                player.netheritePrincipalUsdRaw = player.totalDepositedUsdRaw;
            }

            ListTag positions = playerTag.getList("positions", Tag.TAG_COMPOUND);
            for (int j = 0; j < positions.size(); j++) {
                CompoundTag positionTag = positions.getCompound(j);
                String asset = positionTag.getString("asset");
                long quantity = positionTag.getLong("quantityRaw");
                long averagePrice = positionTag.getLong("averageCostPriceRaw");
                if (!asset.isBlank() && quantity > 0 && averagePrice > 0) {
                    Position position = new Position();
                    position.quantityRaw = quantity;
                    position.averageCostPriceRaw = averagePrice;
                    player.positions.put(asset, position);
                }
            }
            accounting.players.put(playerId, player);
        }
        return accounting;
    }

    private static long multiplySaturated(long first, long second) {
        try {
            return Math.multiplyExact(first, second);
        } catch (ArithmeticException ignored) {
            return (first ^ second) < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static long addSaturated(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException ignored) {
            return second < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static final class PlayerLedger {
        long totalDepositedUsdRaw;
        long netheritePrincipalUsdRaw;
        long realizedProfitUsdRaw;
        long netheriteProfitWithdrawnUsdRaw;
        final Map<String, Position> positions = new HashMap<>();
    }

    private static final class Position {
        long quantityRaw;
        long averageCostPriceRaw;
    }
}
