package dev.polybit.gitmc.mixin;

import dev.polybit.gitmc.block.BlockChangeTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Detects successful player block placements for {@link BlockChangeTracker}.
 *
 * <p>Fabric API has no bundled "block placed" event — unlike breaking, which
 * {@code PlayerBlockBreakEvents.AFTER} covers cleanly. {@link BlockItem#place}
 * is the actual vanilla method that both resolves and applies a placement, so
 * this mixin injects at its head (to capture the pre-placement state at the
 * target position) and its return (to pair that with the post-placement state,
 * only once the placement is confirmed to have succeeded).
 *
 * <p>A {@link Deque} rather than a single field guards against reentrancy —
 * if placing one block were to synchronously trigger another placement of
 * the same {@link BlockItem} type within the same call stack, the before/after
 * pairs would still line up correctly.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Unique
    private final Deque<BlockState> gitmc$beforeStates = new ArrayDeque<>();

    @Inject(method = "place", at = @At("HEAD"))
    private void gitmc$captureBefore(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        Level level = context.getLevel();
        gitmc$beforeStates.push(level.getBlockState(context.getClickedPos()));
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void gitmc$recordPlacement(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        BlockState before = gitmc$beforeStates.pop();

        InteractionResult result = cir.getReturnValue();
        Level level = context.getLevel();
        if (result == null || !result.consumesAction() || level.isClientSide()) {
            return;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return;
        }

        BlockPos pos = context.getClickedPos();
        BlockState after = level.getBlockState(pos);
        BlockChangeTracker.getInstance().recordChange(level.dimension(), pos, before, after);
    }
}
