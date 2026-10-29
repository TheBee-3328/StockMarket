package net.kroia.stockmarket.minecraft.block.custom;

import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.stockmarket.minecraft.item.StockMarketItems;
import net.kroia.stockmarket.stockmarket.market.NetheriteExchangeLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Fixed-rate commodity exchange. Depositing materials mints USD tokens. With
 * USD tokens, sneak-right-click redeems netherite subject to a persistent,
 * player-specific principal and realized-profit limit.
 */
public final class UsdExchangeBlock extends Block {
    public static final String NAME = "usd_exchange_block";

    public UsdExchangeBlock() {
        super(Properties.of().strength(3.5F));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            player.displayClientMessage(Component.literal(
                    "USD Exchange — right-click with a supported material to deposit it for USD. "
                    + "Iron $1, Diamond $100, Netherite $500. "
                    + "Sneak-right-click while holding USD tokens to withdraw capped netherite."), true);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (stack.isEmpty()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        if (stack.is(StockMarketItems.SHARE_USD.get())) {
            return handleNetheriteWithdrawal(level, player);
        }

        int unitValue = getFixedUsdValue(stack.getItem());
        if (unitValue <= 0) {
            player.displayClientMessage(Component.literal(
                    "This item has no fixed USD exchange rate."), true);
            return ItemInteractionResult.CONSUME;
        }

        int itemCount = stack.getCount();
        long totalUsd = (long) unitValue * itemCount;
        long scaleFactor = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        MinecraftServer server = level.getServer();
        if (server == null) {
            player.displayClientMessage(Component.literal("The exchange is unavailable right now."), true);
            return ItemInteractionResult.CONSUME;
        }

        // Record the contribution before removing the material. The ledger is keyed
        // by player UUID, not bank account, and is persisted in the overworld save.
        NetheriteExchangeLedger.get(server).recordDeposit(
                player.getUUID(), unitValue, itemCount,
                stack.is(Items.NETHERITE_INGOT), scaleFactor);

        stack.shrink(itemCount);
        giveItems(player, new ItemStack(StockMarketItems.SHARE_USD.get()), totalUsd);
        player.displayClientMessage(Component.literal(
                "Exchanged " + itemCount + " item(s) for " + totalUsd + " USD token(s)."), true);
        return ItemInteractionResult.CONSUME;
    }

    private ItemInteractionResult handleNetheriteWithdrawal(Level level, Player player) {
        MinecraftServer server = level.getServer();
        if (server == null) return ItemInteractionResult.CONSUME;

        NetheriteExchangeLedger ledger = NetheriteExchangeLedger.get(server);
        long scaleFactor = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        long cap = ledger.getMaxNetheriteWithdrawals(player.getUUID(), scaleFactor);

        if (!player.isShiftKeyDown()) {
            player.displayClientMessage(Component.literal(
                    "Netherite costs $500 per ingot. Sneak-right-click to withdraw. "
                    + "Your current cap: " + cap + " netherite ingot(s)."), true);
            return ItemInteractionResult.CONSUME;
        }

        long availableUsdTokens = countUsdTokens(player.getInventory());
        long affordableIngots = availableUsdTokens / NetheriteExchangeLedger.NETHERITE_USD_VALUE;
        long withdrawalCount = Math.min(cap, affordableIngots);
        if (withdrawalCount <= 0) {
            String reason = cap <= 0
                    ? "Your personal netherite cap is 0. Deposit netherite at this exchange and/or build eligible realized stock-trading profits."
                    : "You need at least $500 in USD tokens in your inventory for each netherite ingot.";
            player.displayClientMessage(Component.literal(reason + " Current cap: " + cap + "."), true);
            return ItemInteractionResult.CONSUME;
        }

        long usdCost = withdrawalCount * NetheriteExchangeLedger.NETHERITE_USD_VALUE;
        // Verify and book the cap before spending the USD tokens. Both operations run
        // synchronously on the server thread; the inventory count was checked above.
        if (!ledger.withdrawNetherite(player.getUUID(), withdrawalCount, scaleFactor)) {
            player.displayClientMessage(Component.literal(
                    "Netherite withdrawal was rejected by your personal cap. No USD tokens were spent."), true);
            return ItemInteractionResult.CONSUME;
        }

        consumeUsdTokens(player.getInventory(), usdCost);
        giveItems(player, new ItemStack(Items.NETHERITE_INGOT), withdrawalCount);
        long updatedCap = ledger.getMaxNetheriteWithdrawals(player.getUUID(), scaleFactor);
        player.displayClientMessage(Component.literal(
                "Withdrew " + withdrawalCount + " netherite ingot(s) for $" + usdCost
                + ". Remaining cap: " + updatedCap + "."), true);
        return ItemInteractionResult.CONSUME;
    }

    private static long countUsdTokens(Inventory inventory) {
        long count = 0;
        for (ItemStack candidate : inventory.items) {
            if (candidate.is(StockMarketItems.SHARE_USD.get())) count += candidate.getCount();
        }
        for (ItemStack candidate : inventory.offhand) {
            if (candidate.is(StockMarketItems.SHARE_USD.get())) count += candidate.getCount();
        }
        return count;
    }

    private static void consumeUsdTokens(Inventory inventory, long amount) {
        long remaining = amount;
        for (ItemStack candidate : inventory.items) {
            if (remaining <= 0) return;
            if (!candidate.is(StockMarketItems.SHARE_USD.get())) continue;
            int taken = (int) Math.min(remaining, candidate.getCount());
            candidate.shrink(taken);
            remaining -= taken;
        }
        for (ItemStack candidate : inventory.offhand) {
            if (remaining <= 0) return;
            if (!candidate.is(StockMarketItems.SHARE_USD.get())) continue;
            int taken = (int) Math.min(remaining, candidate.getCount());
            candidate.shrink(taken);
            remaining -= taken;
        }
    }

    private static void giveItems(Player player, ItemStack template, long count) {
        long remaining = count;
        while (remaining > 0) {
            int amount = (int) Math.min(template.getMaxStackSize(), remaining);
            ItemStack payout = template.copy();
            payout.setCount(amount);
            player.getInventory().add(payout);
            if (!payout.isEmpty()) {
                player.drop(payout, false);
            }
            remaining -= amount;
        }
    }

    /** Returns fixed USD-token value per item, or zero when unsupported. */
    static int getFixedUsdValue(Item item) {
        if (item == Items.COAL || item == Items.CHARCOAL
                || item == Items.RAW_COPPER || item == Items.REDSTONE
                || item == Items.LAPIS_LAZULI) return 1;
        if (item == Items.COPPER_INGOT || item == Items.AMETHYST_SHARD
                || item == Items.QUARTZ) return 2;
        if (item == Items.RAW_IRON || item == Items.IRON_INGOT) return 1;
        if (item == Items.RAW_GOLD) return 10;
        if (item == Items.GOLD_INGOT) return 20;
        if (item == Items.EMERALD) return 50;
        if (item == Items.DIAMOND) return 100;
        if (item == Items.NETHERITE_INGOT) return 500;
        return 0;
    }
}
