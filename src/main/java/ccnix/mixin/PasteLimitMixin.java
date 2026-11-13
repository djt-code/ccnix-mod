package ccnix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A paste of 512 characters or more no longer kicks the player. CC's client cuts a paste to MAX_PASTE_LENGTH (512)
 * bytes, but the server reads it with {@code MoreStreamCodecs.byteBuffer(512)}, which refuses {@code toRead >= limit}:
 * a full-length paste fails to decode and the server drops the connection (CC: Tweaked 1.120 and 1.121; 1.20.1 checked
 * {@code > }). Giving the paste packet a limit one higher makes it {@code > 512}. Only the server reads the packet.
 * CC: Tweaked fixed the check after 1.121.0 (https://github.com/cc-tweaked/CC-Tweaked/issues/2532); this stays for the
 * versions before it, and with the fix it only lets the server take one byte more than CC's client ever sends. If CC
 * changes the call, this finds nothing to change and does nothing ({@code require = 0}).
 */
@Mixin(targets = "dan200.computercraft.shared.network.server.PasteEventComputerMessage", remap = false)
abstract class PasteLimitMixin {
    @ModifyArg(
        method = "<clinit>",
        at = @At(value = "INVOKE", target = "Ldan200/computercraft/shared/network/codec/MoreStreamCodecs;byteBuffer(I)Lnet/minecraft/network/codec/StreamCodec;"),
        require = 0
    )
    private static int ccnix$fullPaste(int limit) {
        return limit + 1;
    }
}
