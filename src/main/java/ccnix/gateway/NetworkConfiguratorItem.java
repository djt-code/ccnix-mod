package ccnix.gateway;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * The Network Configurator: used on a Network Gateway (main hand, or off hand with the main empty), it opens its
 * dialog; on a CC cable or wired modem, it says what's on that cable network (CableSurvey).
 */
public final class NetworkConfiguratorItem extends Item {
    public NetworkConfiguratorItem(Properties properties) {
        super(properties);
    }

    /** Before the block's own use, so a modem it's used on isn't turned on or off: a cable network's survey. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        var level = context.getLevel();
        var pos = context.getClickedPos();
        if (!CableSurvey.isCable(level.getBlockState(pos))) return InteractionResult.PASS;
        if (level instanceof ServerLevel server && context.getPlayer() instanceof ServerPlayer player) {
            CableSurvey.show(player, server, pos, context.getClickedFace());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.ccnix.network_configurator.tip").withStyle(ChatFormatting.GRAY));
    }
}
