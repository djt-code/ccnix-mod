package ccnix;

import ccnix.gateway.GatewayBlock;
import ccnix.gateway.GatewayBlockEntity;
import ccnix.gateway.GatewayFilter;
import ccnix.gateway.GatewayScreenData;
import ccnix.gateway.GatewayItem;
import ccnix.gateway.Service;
import ccnix.rom.RomPack;
import dan200.computercraft.api.network.wired.WiredElementCapability;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * ccnix: the ccnix repo's programs in every computer's ROM (ccnix.rom), and the Network Gateway, a block joining CC's
 * wired network to a real one through a TUN device on the server (ccnix.gateway).
 */
@Mod(Ccnix.ID)
public final class Ccnix {
    public static final String ID = "ccnix";

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);

    public static final DeferredBlock<GatewayBlock> GATEWAY = BLOCKS.register("gateway", () -> new GatewayBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(1.5f).sound(SoundType.STONE)));
    public static final DeferredItem<BlockItem> GATEWAY_ITEM = ITEMS.register("gateway",
        () -> new GatewayItem(GATEWAY.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GatewayBlockEntity>> GATEWAY_ENTITY =
        BLOCK_ENTITIES.register("gateway", () -> BlockEntityType.Builder.of(GatewayBlockEntity::new, GATEWAY.get()).build(null));

    public Ccnix(IEventBus bus, ModContainer container) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);

        bus.addListener((RegisterCapabilitiesEvent e) ->
            e.registerBlockEntity(WiredElementCapability.get(), GATEWAY_ENTITY.get(), (be, side) -> be.element()));
        bus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) e.accept(GATEWAY_ITEM);
        });
        bus.addListener(RomPack::addTo);
        bus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1")
            .playToClient(GatewayScreenData.TYPE, GatewayScreenData.STREAM_CODEC, GatewayScreenData::handle)
            .playToServer(GatewayFilter.TYPE, GatewayFilter.STREAM_CODEC, GatewayFilter::handle));
        bus.addListener((ModConfigEvent.Reloading e) -> {
            if (e.getConfig().getSpec() == Config.SPEC) Service.reconfigure();
        });

        NeoForge.EVENT_BUS.addListener((ServerStartingEvent e) -> Service.start());
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> Service.stop());
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> CcnixCommand.register(e.getDispatcher()));
    }
}
