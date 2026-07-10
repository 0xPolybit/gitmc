package dev.polybit.gitmc.block;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks block placements and removals caused directly by players since the
 * last {@code /git commit}, for the {@code /git status} overlay.
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
 * <h2>Overlay visibility</h2>
 * Independent of the tracked data, the overlay has three visibility modes
 * (see {@link OverlayMode}): hidden, shown until explicitly hidden
 * ({@code /git status show}), or shown for a fixed window that then fades
 * out on its own ({@code /git status} with no argument). {@link #currentOpacity()}
 * is the single source of truth the renderer reads each frame; it also
 * governs the "N seconds until it disappears" timed behavior without any
 * background thread — it's a pure function of wall-clock time.
 *
 * <h2>Singleplayer/LAN scope</h2>
 * This class is a single instance shared by the client and the integrated
 * server in the same JVM, which is how the {@code /git status} command
 * (executed on the logical server) reaches the renderer (running on the
 * client) without a network channel. On a real dedicated server with remote
 * clients, this does not yet propagate — see the project roadmap.
 */
public final class BlockChangeTracker {

    /** How the overlay is currently being shown. */
    public enum OverlayMode {
        /** Not rendered. */
        HIDDEN,
        /** Rendered at full opacity until {@code /git status hide}. */
        PERSISTENT,
        /**
         * Rendered at full opacity for {@link #TIMED_VISIBLE_MILLIS}, then
         * fades out over {@link #TIMED_FADE_MILLIS}, then behaves as hidden —
         * all without changing mode, since {@link #currentOpacity()} derives
         * this purely from elapsed time.
         */
        TIMED
    }

    /** How long the {@code /git status} (no-argument) overlay stays at full opacity. */
    public static final long TIMED_VISIBLE_MILLIS = 30_000L;

    /** How long the fade-out takes once {@link #TIMED_VISIBLE_MILLIS} elapses. */
    public static final long TIMED_FADE_MILLIS = 3_000L;

    private static final BlockChangeTracker INSTANCE = new BlockChangeTracker();

    private final Map<ResourceKey<Level>, Map<BlockPos, BlockDelta>> byDimension = new ConcurrentHashMap<>();
    private volatile OverlayMode overlayMode = OverlayMode.HIDDEN;
    private volatile long overlayShownAtMillis;

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

    /** {@code /git status show}: overlay stays fully visible until {@link #hide()}. */
    public void showPersistent() {
        overlayMode = OverlayMode.PERSISTENT;
    }

    /**
     * {@code /git status} (no argument): overlay is fully visible for
     * {@link #TIMED_VISIBLE_MILLIS}, then fades out over
     * {@link #TIMED_FADE_MILLIS}. Calling this again (e.g. re-running the
     * bare command) restarts the window from full opacity.
     */
    public void showTimed() {
        overlayMode = OverlayMode.TIMED;
        overlayShownAtMillis = System.currentTimeMillis();
    }

    /** {@code /git status hide}: overlay stops rendering immediately, canceling any timer. */
    public void hide() {
        overlayMode = OverlayMode.HIDDEN;
    }

    /**
     * The alpha multiplier (0.0–1.0) the overlay should currently be
     * rendered at. Pure function of wall-clock time and the current mode —
     * no ticking or scheduled task drives the {@link OverlayMode#TIMED}
     * countdown or fade; each frame just asks "what should opacity be right
     * now", which naturally reaches 0 once the window elapses.
     */
    public float currentOpacity() {
        return switch (overlayMode) {
            case HIDDEN -> 0f;
            case PERSISTENT -> 1f;
            case TIMED -> {
                long elapsed = System.currentTimeMillis() - overlayShownAtMillis;
                if (elapsed < TIMED_VISIBLE_MILLIS) {
                    yield 1f;
                }
                long fadeElapsed = elapsed - TIMED_VISIBLE_MILLIS;
                if (fadeElapsed >= TIMED_FADE_MILLIS) {
                    yield 0f;
                }
                yield 1f - ((float) fadeElapsed / TIMED_FADE_MILLIS);
            }
        };
    }

    /** Convenience for callers that only need a yes/no, e.g. an early-return before rendering. */
    public boolean isOverlayVisible() {
        return currentOpacity() > 0f;
    }
}
