package dev.polybit.gitmc.block;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks block placements and removals caused directly by players since the
 * last {@code /git commit}, for the {@code /git status show|hide} overlay.
 *
 * <h2>Scope</h2>
 * Only direct player actions are recorded: placing a block (see the
 * {@code BlockItemMixin}) or breaking one ({@code PlayerBlockBreakEvents.AFTER},
 * registered in {@code GitMC.onInitialize}). Physics-driven changes — pistons,
 * liquids, crop growth, redstone, falling blocks, explosions — are
 * deliberately not tracked; including them would flood the overlay with
 * noise unrelated to what a player actually built or destroyed.
 *
 * <h2>Lifetime</h2>
 * Tracking is in-memory only and resets when the game process restarts — this
 * is a live "diff since last commit" view, not a persisted log. A successful
 * {@code /git commit} clears it, since everything up to that point is now
 * part of the repository's history.
 *
 * <h2>Singleplayer/LAN scope</h2>
 * This class is a single instance shared by the client and the integrated
 * server in the same JVM, which is how the {@code /git status show|hide}
 * toggle (executed on the logical server, via a command) reaches the
 * renderer (running on the client) without a network channel. On a real
 * dedicated server with remote clients, this does not yet propagate —
 * see the project roadmap.
 */
public final class BlockChangeTracker {

    private static final BlockChangeTracker INSTANCE = new BlockChangeTracker();

    private final Map<ResourceKey<Level>, Map<BlockPos, BlockDelta>> byDimension = new ConcurrentHashMap<>();
    private volatile boolean overlayVisible = true;

    private BlockChangeTracker() {
    }

    public static BlockChangeTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Records a player-caused change at {@code pos} in {@code dimension}.
     *
     * <p>The first time a position is touched since the last commit,
     * {@code before} becomes its tracked "original" state; later touches to
     * the same position only update the "current" state, leaving the
     * original in place. If a position is changed back to its original
     * state (e.g. placed then broken again), tracking for it is dropped —
     * net-zero change, nothing to show.
     */
    public void recordChange(ResourceKey<Level> dimension, BlockPos pos, BlockState before, BlockState after) {
        if (before.equals(after)) {
            return;
        }
        Map<BlockPos, BlockDelta> positions =
            byDimension.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>());
        positions.compute(pos.immutable(), (ignored, existing) -> {
            BlockState original = existing == null ? before : existing.original();
            if (original.equals(after)) {
                return null;
            }
            return new BlockDelta(original, after);
        });
    }

    /** Immutable snapshot of tracked changes in {@code dimension}, for rendering or reporting. */
    public Map<BlockPos, BlockDelta> snapshot(ResourceKey<Level> dimension) {
        Map<BlockPos, BlockDelta> positions = byDimension.get(dimension);
        return positions == null ? Map.of() : Map.copyOf(positions);
    }

    /** Total tracked changes across all dimensions. */
    public int totalCount() {
        return byDimension.values().stream().mapToInt(Map::size).sum();
    }

    /** Clears all tracked changes. Called after a successful {@code /git commit}. */
    public void clear() {
        byDimension.clear();
    }

    public boolean isOverlayVisible() {
        return overlayVisible;
    }

    public void setOverlayVisible(boolean visible) {
        overlayVisible = visible;
    }
}
