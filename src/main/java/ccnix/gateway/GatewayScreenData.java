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
 * Server to client: a gateway block's dialog. Sent when a Network Configurator opens it and after a change (refresh false:
 * opened if it isn't), and each second it's open, answering {@link GatewayPoll} (refresh true: only an open dialog
 * takes it). Its title and where it is, its state (WORKING, STANDBY, DOWN) and what that means, rows of what it is and
 * has heard, its frame counts (in, out, from other blocks, filtered; none unless it's working), its VLANs and native
 * VLAN, whether this player may change them, and what the last change said.
 */
public record GatewayScreenData(BlockPos pos, boolean refresh, String title, String subtitle, int state, String stateText,
                                List<Row> rows, List<Long> counts, String vlans, String nativeVlan, boolean canEdit,
                                String message) implements CustomPacketPayload {
    public static final int WORKING = 0, STANDBY = 1, DOWN = 2;

    /** A label, its value, and more said when the pointer is over it ("" none; lines split at \n). */
    public record Row(String label, String value, String more) {
        static final StreamCodec<RegistryFriendlyByteBuf, Row> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Row::label,
            ByteBufCodecs.STRING_UTF8, Row::value,
            ByteBufCodecs.STRING_UTF8, Row::more,
            Row::new);
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Row>> ROWS = Row.STREAM_CODEC.apply(ByteBufCodecs.list());
    private static final StreamCodec<io.netty.buffer.ByteBuf, List<Long>> COUNTS = ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list());

    public static final Type<GatewayScreenData> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Ccnix.ID, "gateway_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GatewayScreenData> STREAM_CODEC =
        StreamCodec.of((buf, d) -> d.write(buf), GatewayScreenData::read);

    private void write(RegistryFriendlyByteBuf buf) {
        BlockPos.STREAM_CODEC.encode(buf, pos);
        buf.writeBoolean(refresh);
        buf.writeUtf(title);
        buf.writeUtf(subtitle);
        buf.writeVarInt(state);
        buf.writeUtf(stateText);
        ROWS.encode(buf, rows);
        COUNTS.encode(buf, counts);
        buf.writeUtf(vlans);
        buf.writeUtf(nativeVlan);
        buf.writeBoolean(canEdit);
        buf.writeUtf(message);
    }

    private static GatewayScreenData read(RegistryFriendlyByteBuf buf) {
        return new GatewayScreenData(BlockPos.STREAM_CODEC.decode(buf), buf.readBoolean(), buf.readUtf(), buf.readUtf(),
            buf.readVarInt(), buf.readUtf(), ROWS.decode(buf), COUNTS.decode(buf), buf.readUtf(), buf.readUtf(),
            buf.readBoolean(), buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GatewayScreenData data, IPayloadContext ctx) {
        if (FMLEnvironment.dist.isClient()) ClientAccess.gateway(data);
    }
}
