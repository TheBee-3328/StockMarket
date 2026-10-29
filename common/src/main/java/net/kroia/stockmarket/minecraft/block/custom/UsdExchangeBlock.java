package net.kroia.stockmarket.minecraft.block.custom;

import net.kroia.stockmarket.minecraft.item.StockMarketItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Fixed-rate exchange kiosk. These commodity rates are intentionally static:
 * only the share-token markets fluctuate, never the values used at this block.
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
                    "USD Exchange — hold a supported item and right-click: "
                    + "Coal $1, Copper $2, Iron $5, Gold $20, Emerald $50, "
                    + "Diamond $100, Netherite Ingot $500 per item."), true);
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
            player.displayClientMessage(Component.literal(
                    "USD tokens are the exchange payout; they cannot be exchanged back here."), true);
            return ItemInteractionResult.CONSUME;
        }

        int unitValue = getFixedUsdValue(stack.getItem());
        if (unitValue <= 0) {
            player.displayClientMessage(Component.literal(
                    "This item has no fixed USD exchange rate."), true);
            return ItemInteractionResult.CONSUME;
        }

        int itemCount = stack.getCount();
        long totalUsd = (long) unitValue * itemCount;
        stack.shrink(itemCount);

        long remaining = totalUsd;
        while (remaining > 0) {
            int amount = (int) Math.min(64L, remaining);
            ItemStack payout = new ItemStack(StockMarketItems.SHARE_USD.get(), amount);
            // If the inventory is full, drop the remainder instead of deleting value.
            if (!player.getInventory().add(payout) && !payout.isEmpty()) {
                player.drop(payout, false);
            }
            remaining -= amount;
        }

        player.displayClientMessage(Component.literal(
                "Exchanged " + itemCount + " item(s) for " + totalUsd + " USD token(s)."), true);
        return ItemInteractionResult.CONSUME;
    }

    /** Returns fixed USD-token value per item, or zero when unsupported. */
    static int getFixedUsdValue(Item item) {
        if (item == Items.COAL || item == Items.CHARCOAL
                || item == Items.RAW_COPPER || item == Items.REDSTONE
                || item == Items.LAPIS_LAZULI) return 1;
        if (item == Items.COPPER_INGOT || item == Items.AMETHYST_SHARD
                || item == Items.QUARTZ) return 2;
        if (item == Items.RAW_IRON) return 3;
        if (item == Items.IRON_INGOT) return 5;
        if (item == Items.RAW_GOLD) return 10;
        if (item == Items.GOLD_INGOT) return 20;
        if (item == Items.EMERALD) return 50;
        if (item == Items.DIAMOND) return 100;
        if (item == Items.NETHERITE_INGOT) return 500;
        return 0;
    }
}
