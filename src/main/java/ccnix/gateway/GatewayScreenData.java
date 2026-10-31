package ccnix.gateway;

import ccnix.Ccnix;
import ccnix.client.ClientAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Server to client: a gateway block's dialog, opened by its right-click (and again after a change): its lines, its
 * VLANs and native VLAN, whether this player may change them, and what the last change said.
 */
public record GatewayScreenData(BlockPos pos, List<String> lines, String vlans, String nativeVlan, boolean canEdit,
                                String message) implements CustomPacketPayload {
    public static final Type<GatewayScreenData> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Ccnix.ID, "gateway_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GatewayScreenData> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, GatewayScreenData::pos,
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), GatewayScreenData::lines,
        ByteBufCodecs.STRING_UTF8, GatewayScreenData::vlans,
        ByteBufCodecs.STRING_UTF8, GatewayScreenData::nativeVlan,
        ByteBufCodecs.BOOL, GatewayScreenData::canEdit,
        ByteBufCodecs.STRING_UTF8, GatewayScreenData::message,
        GatewayScreenData::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GatewayScreenData data, IPayloadContext ctx) {
        if (FMLEnvironment.dist.isClient()) ClientAccess.openGateway(data);
    }
}
