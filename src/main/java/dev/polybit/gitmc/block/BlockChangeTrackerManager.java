package dev.polybit.gitmc.block;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-server registry of {@link BlockChangeTracker}s, one per loaded
 * ServerLevel (world). Trackers are created lazily by {@code /git init}
 * and reloaded from disk when a world finishes loading.
 *
 * <p>Also maintains a per-world set of chunks that have been loaded
 * since the server started. The tracker queries this set when capturing
 * the baseline or computing deltas, because Minecraft 26.2 doesn't
 * expose a public way to iterate all currently-loaded chunks.
 */
public final class BlockChangeTrackerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockChangeTrackerManager.class);

    private static final Map<ResourceKey<Level>, BlockChangeTracker> BY_WORLD = new HashMap<>();
    private static final Map<ResourceKey<Level>, Set<ChunkPos>> LOADED_CHUNKS = new HashMap<>();

    private BlockChangeTrackerManager() {
    }

    /** Register Fabric lifecycle hooks. Idempotent. */
    public static void register() {
        ServerLevelEvents.LOAD.register((server, level) -> {
            BlockChangeTracker existing = BY_WORLD.get(level.dimension());
            if (existing != null) return;
            BlockChangeTracker loaded = BlockChangeTracker.tryLoad(level);
            if (loaded != null) {
                BY_WORLD.put(level.dimension(), loaded);
            }
        });

        ServerLevelEvents.UNLOAD.register((server, level) -> {
            BlockChangeTracker removed = BY_WORLD.remove(level.dimension());
            LOADED_CHUNKS.remove(level.dimension());
            if (removed != null) {
                LOGGER.info("Unloading tracker for {}", level.dimension());
            }
        });

        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, _new) -> {
            ResourceKey<Level> dim = level.dimension();
            LOADED_CHUNKS
                .computeIfAbsent(dim, k -> new HashSet<>())
                .add(chunk.getPos());
        });
    }

    /**
     * Initialize the tracker for {@code level}, overwriting any prior
     * baseline. Saved to disk atomically.
     */
    public static BlockChangeTracker initialize(ServerLevel level) {
        BlockChangeTracker tracker = BlockChangeTracker.initialize(level);
        BY_WORLD.put(level.dimension(), tracker);
        return tracker;
    }

    /**
     * Look up the tracker for {@code level}'s dimension, or
     * {@code null} if {@code /git init} hasn't been run.
     */
    public static BlockChangeTracker get(ServerLevel level) {
        return BY_WORLD.get(level.dimension());
    }

    /**
     * All chunks that have been loaded in {@code level}'s dimension since
     * the server started, including chunks that may have unloaded again.
     * Used by the tracker to know what range to scan.
     */
    public static Set<ChunkPos> getLoadedChunks(ServerLevel level) {
        return Collections.unmodifiableSet(
            LOADED_CHUNKS.getOrDefault(level.dimension(), Collections.emptySet()));
    }

    /** All currently-loaded trackers, for diagnostics. */
    public static Map<ResourceKey<Level>, BlockChangeTracker> all() {
        return Map.copyOf(BY_WORLD);
    }
}
