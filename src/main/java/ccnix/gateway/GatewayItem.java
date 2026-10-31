package ccnix.gateway;

import ccnix.Config;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** The gateway as an item: a player may place gateway.maxPerPlayer of them (0: any number). */
public final class GatewayItem extends BlockItem {
    public GatewayItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext ctx) {
        if (ctx.getPlayer() instanceof ServerPlayer player) {
            int max = Config.MAX_PER_PLAYER.get();
            int have = max > 0 ? GatewayRegistry.get(player.server).count(player.getUUID()) : 0;
            if (max > 0 && have >= max) {
                player.displayClientMessage(Component.literal("You have " + have + " Network Gateways placed: this server"
                    + " allows " + max + " each. Break one to place another."), false);
                return InteractionResult.FAIL;
            }
        }
        return super.place(ctx);
    }
}
