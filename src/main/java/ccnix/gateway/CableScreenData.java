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
 * Server to client: what a Network Configurator found on a cable network (CableSurvey), for its dialog. Where it was
 * used, a title and a line under it, and sections: each a heading ("" none), what the pointer over it says, whether
 * it's a warning, its rows, and what to say when it has none ("" leaves it out).
 */
public record CableScreenData(BlockPos pos, String title, String subtitle, List<Section> sections) implements CustomPacketPayload {
    public record Section(String heading, String more, boolean warn, List<DialogRow> rows, String empty) {
        static final StreamCodec<RegistryFriendlyByteBuf, Section> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Section::heading,
            ByteBufCodecs.STRING_UTF8, Section::more,
            ByteBufCodecs.BOOL, Section::warn,
            DialogRow.LIST, Section::rows,
            ByteBufCodecs.STRING_UTF8, Section::empty,
            Section::new);
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Section>> SECTIONS =
        Section.STREAM_CODEC.apply(ByteBufCodecs.list());

    public static final Type<CableScreenData> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Ccnix.ID, "cable_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CableScreenData> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, CableScreenData::pos,
        ByteBufCodecs.STRING_UTF8, CableScreenData::title,
        ByteBufCodecs.STRING_UTF8, CableScreenData::subtitle,
        SECTIONS, CableScreenData::sections,
        CableScreenData::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CableScreenData data, IPayloadContext ctx) {
        if (FMLEnvironment.dist.isClient()) ClientAccess.cable(data);
    }
}
