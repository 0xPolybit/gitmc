package dev.polybit.gitmc.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block captured in the baseline: a position and its block state
 * (block type + all state properties).
 *
 * <p>Block-state ids are the canonical way to compare two block states
 * without serializing them; Minecraft guarantees that two states with the
 * same id are semantically equivalent (same block, same properties).
 */
public record BlockSnapshot(BlockPos pos, int stateId) {

    public static BlockSnapshot of(BlockPos pos, BlockState state) {
        return new BlockSnapshot(pos.immutable(), state.hashCode());
    }
}
