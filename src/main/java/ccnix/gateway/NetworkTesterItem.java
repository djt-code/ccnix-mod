package ccnix.gateway;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** The Network Tester: used on a Network Gateway (main hand, or off hand with the main empty), it opens its dialog. */
public final class NetworkTesterItem extends Item {
    public NetworkTesterItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.ccnix.network_tester.tip").withStyle(ChatFormatting.GRAY));
    }
}
