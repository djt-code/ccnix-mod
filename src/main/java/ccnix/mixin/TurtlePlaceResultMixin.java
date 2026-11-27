package ccnix.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code turtle.place()} says it placed an item that places itself in {@code onItemUseFirst} (an AE2 facade, for
 * one). CC: Tweaked's NeoForge {@code PlatformHelperImpl.useOn} calls the item's {@code onItemUseFirst} and, when
 * that does something, hands back the click event's cancellation result instead of the item's: for an event nobody
 * cancelled that is {@code PASS}. The turtle then takes the place as not done, tries its other positions (the block
 * beyond, the one below, itself), which can place a second item, and fails with "Cannot place item here". Here the
 * item's own result is handed back. Checked in CC: Tweaked 1.120.2 (and its mc-1.21.x and mc-1.20.x branches);
 * written up in ccnix's doc/bugs/cc-tweaked-place-onitemusefirst.md. If CC changes the call, this finds nothing to
 * change and does nothing ({@code require = 0}), and turtlelib still goes by the item count.
 */
@Mixin(targets = "dan200.computercraft.shared.platform.PlatformHelperImpl", remap = false)
abstract class TurtlePlaceResultMixin {
    @ModifyExpressionValue(
        method = "useOn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent$RightClickBlock;getCancellationResult()Lnet/minecraft/world/InteractionResult;",
            ordinal = 1
        ),
        require = 0
    )
    private InteractionResult ccnix$itemsResult(InteractionResult cancellation, @Local(ordinal = 0) InteractionResult result) {
        return result;
    }
}
