package ccnix.jei;

import ccnix.Ccnix;
import ccnix.client.Remote;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * JEI's Information pages for the gateway and the Network Configurator. JEI finds this by its annotation, so without
 * JEI nothing loads it. JEI registers recipes on the client on joining a world; on a server without ccnix there are no
 * items to describe (ccnix.client.Remote).
 */
@JeiPlugin
public final class CcnixJei implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Ccnix.ID, "jei");
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        if (!Remote.hasCcnix()) return;
        registration.addItemStackInfo(new ItemStack(Ccnix.GATEWAY_ITEM.get()), Component.translatable("ccnix.jei.gateway"));
        registration.addItemStackInfo(new ItemStack(Ccnix.NETWORK_CONFIGURATOR.get()),
            Component.translatable("ccnix.jei.network_configurator"));
    }
}
