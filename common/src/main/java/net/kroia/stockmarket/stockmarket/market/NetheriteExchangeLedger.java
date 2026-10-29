package net.kroia.stockmarket.stockmarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.HolderLookup;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-persistent wrapper for per-player USD-exchange netherite accounting.
 * Data is kept in the overworld so it survives dimension changes and restarts.
 */
public final class NetheriteExchangeLedger extends SavedData {
    private static final String DATA_NAME = "stockmarket_netherite_exchange";
    private final NetheriteExchangeAccounting accounting;

    private NetheriteExchangeLedger() {
        this(new NetheriteExchangeAccounting());
    }

    private NetheriteExchangeLedger(NetheriteExchangeAccounting accounting) {
        this.accounting = accounting;
    }

    public static NetheriteExchangeLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        NetheriteExchangeLedger::new,
                        NetheriteExchangeLedger::load,
                        DataFixTypes.LEVEL),
                DATA_NAME);
    }

    public void recordDeposit(java.util.UUID playerId, long unitValueUsd, long itemCount,
                              boolean isNetherite, long scaleFactor) {
        accounting.recordDeposit(playerId, unitValueUsd, itemCount, isNetherite, scaleFactor);
        setDirty();
    }

    public boolean recordTrade(java.util.UUID playerId, String assetKey, long signedVolumeRaw,
                               long executionPriceRaw, long scaleFactor) {
        boolean changed = accounting.recordTrade(
                playerId, assetKey, signedVolumeRaw, executionPriceRaw, scaleFactor);
        if (changed) setDirty();
        return changed;
    }

    public long getMaxNetheriteWithdrawals(java.util.UUID playerId, long scaleFactor) {
        return accounting.getMaxNetheriteWithdrawals(playerId, scaleFactor);
    }

    public boolean withdrawNetherite(java.util.UUID playerId, long count, long scaleFactor) {
        boolean withdrawn = accounting.withdrawNetherite(playerId, count, scaleFactor);
        if (withdrawn) setDirty();
        return withdrawn;
    }

    public long getNetheritePrincipalUsdRaw(java.util.UUID playerId) {
        return accounting.getNetheritePrincipalUsdRaw(playerId);
    }

    public long getTotalDepositedUsdRaw(java.util.UUID playerId) {
        return accounting.getTotalDepositedUsdRaw(playerId);
    }

    public long getRealizedProfitUsdRaw(java.util.UUID playerId) {
        return accounting.getRealizedProfitUsdRaw(playerId);
    }

    private static NetheriteExchangeLedger load(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag accountingTag = tag.contains("accounting", net.minecraft.nbt.Tag.TAG_COMPOUND)
                ? tag.getCompound("accounting") : new CompoundTag();
        return new NetheriteExchangeLedger(NetheriteExchangeAccounting.load(accountingTag));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.put("accounting", accounting.save());
        return tag;
    }
}
