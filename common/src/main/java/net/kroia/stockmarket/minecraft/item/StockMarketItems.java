package net.kroia.stockmarket.minecraft.item;

import com.google.common.base.Suppliers;
import dev.architectury.registry.registries.Registrar;
import dev.architectury.registry.registries.RegistrarManager;
import dev.architectury.registry.registries.RegistrySupplier;
import net.kroia.stockmarket.StockMarketMod;
import net.kroia.stockmarket.minecraft.item.custom.NewspaperItem;
import net.kroia.stockmarket.minecraft.item.custom.software.TradingSoftware;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.function.Supplier;

public class StockMarketItems {
    // 1.19.4
    public static final Supplier<RegistrarManager> MANAGER = Suppliers.memoize(() -> RegistrarManager.get(StockMarketMod.MOD_ID));
    public static final Registrar<Item> ITEMS = MANAGER.get().get(Registries.ITEM);

    // 1.19.3 or below
    //public static final Supplier<Registries> REGISTRIES = Suppliers.memoize(() -> Registries.get(BankSystemMod.MOD_ID));
    //public static final Registrar<Item> ITEMS = REGISTRIES.get().get(Registry.ITEM_KEY);

    private static boolean initialized = false;
    public static void init(){
        // Force the class to be loaded to initialize static fields
        if(initialized) return;
        initialized = true;
    }

    // Software
    public static final RegistrySupplier<Item> TRADING_SOFTWARE = registerItem(TradingSoftware.NAME, TradingSoftware::new);

    /** Newspaper (T-074): right-click opens the news screen. */
    public static final RegistrySupplier<Item> NEWSPAPER = registerItem(NewspaperItem.NAME, NewspaperItem::new);


    

    // Dedicated market-share items used by the optional Finnhub feed.
    /** Stable USD quote-unit token used as the fixed HAVE leg in pair mode. */
    public static final RegistrySupplier<Item> SHARE_USD = registerShare("share_usd");
    public static final RegistrySupplier<Item> SHARE_XAU = registerShare("share_xau");
    public static final RegistrySupplier<Item> SHARE_NAS100 = registerShare("share_nas100");
    public static final RegistrySupplier<Item> SHARE_SP500 = registerShare("share_sp500");
    public static final RegistrySupplier<Item> SHARE_EURUSD = registerShare("share_eurusd");
    public static final RegistrySupplier<Item> SHARE_BTC = registerShare("share_btc");
    public static final RegistrySupplier<Item> SHARE_NVDA = registerShare("share_nvda");
    public static final RegistrySupplier<Item> SHARE_GOOGL = registerShare("share_googl");
    public static final RegistrySupplier<Item> SHARE_GOOG = registerShare("share_goog");
    public static final RegistrySupplier<Item> SHARE_AAPL = registerShare("share_aapl");
    public static final RegistrySupplier<Item> SHARE_MSFT = registerShare("share_msft");
    public static final RegistrySupplier<Item> SHARE_JPM = registerShare("share_jpm");
    public static final RegistrySupplier<Item> SHARE_XOM = registerShare("share_xom");
    public static final RegistrySupplier<Item> SHARE_LLY = registerShare("share_lly");
    public static final RegistrySupplier<Item> SHARE_WMT = registerShare("share_wmt");
    public static final RegistrySupplier<Item> SHARE_CAT = registerShare("share_cat");
    public static final RegistrySupplier<Item> SHARE_V = registerShare("share_v");
    public static final RegistrySupplier<Item> SHARE_DIS = registerShare("share_dis");
    public static final RegistrySupplier<Item> SHARE_TSLA = registerShare("share_tsla");

    private static RegistrySupplier<Item> registerShare(String name) {
        return registerItem(name, () -> new Item(
                new Item.Properties().arch$tab(StockMarketCreativeModeTab.STOCK_MARKET_TAB)));
    }

    /** Returns whether an item is one of the real-stock counterpart tokens (excluding USD). */
    public static boolean isUsdQuotedAsset(Item item) {
        return item == SHARE_XAU.get()
                || item == SHARE_NAS100.get()
                || item == SHARE_SP500.get()
                || item == SHARE_EURUSD.get()
                || item == SHARE_BTC.get()
                || item == SHARE_NVDA.get()
                || item == SHARE_GOOGL.get()
                || item == SHARE_GOOG.get()
                || item == SHARE_AAPL.get()
                || item == SHARE_MSFT.get()
                || item == SHARE_JPM.get()
                || item == SHARE_XOM.get()
                || item == SHARE_LLY.get()
                || item == SHARE_WMT.get()
                || item == SHARE_CAT.get()
                || item == SHARE_V.get()
                || item == SHARE_DIS.get()
                || item == SHARE_TSLA.get();
    }

public static <T extends Item> RegistrySupplier<T> registerItem(String name, Supplier<T> item)
    {
        //BankSystemMod.LOGGER.info("Registering Item: " + name);
        return ITEMS.register(ResourceLocation.fromNamespaceAndPath(StockMarketMod.MOD_ID, name), item);
    }
    public static <T extends Block> RegistrySupplier<Item> registerBlockItem(String name, RegistrySupplier<T> block)
    {
        //return registerItem(name, () -> new BlockItem(block.get(), new Item.Properties().tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB))); // 1.19.3 or below
        return registerItem(name, () -> new BlockItem(block.get(), new Item.Properties().arch$tab(StockMarketCreativeModeTab.STOCK_MARKET_TAB)));
    }
}
