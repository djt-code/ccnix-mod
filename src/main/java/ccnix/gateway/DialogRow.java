package ccnix.gateway;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/** A row of one of the mod's dialogs: a label, its value, and more said when the pointer is over it ("" none; lines split at \n). */
public record DialogRow(String label, String value, String more) {
    static final StreamCodec<RegistryFriendlyByteBuf, DialogRow> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, DialogRow::label,
        ByteBufCodecs.STRING_UTF8, DialogRow::value,
        ByteBufCodecs.STRING_UTF8, DialogRow::more,
        DialogRow::new);
    static final StreamCodec<RegistryFriendlyByteBuf, List<DialogRow>> LIST = STREAM_CODEC.apply(ByteBufCodecs.list());
}
