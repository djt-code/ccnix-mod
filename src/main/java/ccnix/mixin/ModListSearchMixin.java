package ccnix.mixin;

import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Mods screen's search finds mods again with the list scrolled. NeoForge's {@code ModListWidget.refreshList}
 * refills the list on every change to the search but keeps the old scroll position, so a few matches sit above the
 * top of the list and it looks empty (https://github.com/neoforged/NeoForge/issues/3655). Clamping the scroll after
 * the refill, as vanilla's world list does after its search, brings them into view. Client only; if NeoForge changes
 * the method, this finds nothing and does nothing ({@code require = 0}).
 */
@Mixin(targets = "net.neoforged.neoforge.client.gui.widget.ModListWidget", remap = false)
abstract class ModListSearchMixin {
    @Inject(method = "refreshList", at = @At("TAIL"), require = 0)
    private void ccnix$clampScroll(CallbackInfo ci) {
        ((AbstractSelectionList<?>) (Object) this).clampScrollAmount();
    }
}
