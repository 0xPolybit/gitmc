package dev.polybit.gitmc.block;

import net.minecraft.world.level.block.state.BlockState;

/**
 * A single position's block state change since the last {@code /git commit},
 * as tracked by {@link BlockChangeTracker}.
 *
 * @param original the block state at this position when it was first touched
 *                 since the last commit
 * @param current  the block state at this position right now
 */
public record BlockDelta(BlockState original, BlockState current) {

    /** How a position's state changed, for status reporting and overlay coloring. */
    public enum Kind {
        /** Was air, now isn't — a block was placed where none existed. */
        ADDED,
        /** Wasn't air, now is — a block was removed. */
        REMOVED,
        /** Neither state is air, but they differ — one block replaced another. */
        MODIFIED
    }

    public Kind kind() {
        boolean wasAir = original.isAir();
        boolean isAir = current.isAir();
        if (wasAir && !isAir) {
            return Kind.ADDED;
        }
        if (!wasAir && isAir) {
            return Kind.REMOVED;
        }
        return Kind.MODIFIED;
    }
}
