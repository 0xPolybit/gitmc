package dev.polybit.gitmc.block;

import net.minecraft.core.BlockPos;

/**
 * The difference between a position in the baseline and the same position
 * in the current world.
 *
 * <p>Modelled as a sealed interface so the renderer (and the chat output,
 * if we add it later) can {@code switch} on the cases exhaustively.
 */
public sealed interface BlockDelta {

    BlockPos pos();

    /**
     * A block exists at this position now but not in the baseline —
     * i.e. the player placed it since {@code /git init}. Rendered
     * translucent green.
     */
    record Untracked(BlockPos pos, int currentStateId) implements BlockDelta {}

    /**
     * A block was in the baseline and still exists, but is now a
     * different block type or has different state properties
     * (door open/closed, water level, farmland wetness, etc.). Rendered
     * translucent yellow.
     */
    record Modified(BlockPos pos, int baselineStateId, int currentStateId) implements BlockDelta {}

    /**
     * A block was in the baseline and is now gone (the position is air,
     * or the chunk no longer contains a block here). Rendered translucent
     * red.
     */
    record Removed(BlockPos pos, int baselineStateId) implements BlockDelta {}
}
