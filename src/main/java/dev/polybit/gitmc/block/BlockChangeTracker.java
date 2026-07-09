package dev.polybit.gitmc.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-world block-level change tracker.
 *
 * <p>Holds the snapshot of blocks taken on {@code /git init} and provides
 * the comparison logic for {@code /git status}. The baseline is persisted
 * to {@code <world>/gitmc/baseline.nbt} so it survives server restarts.
 *
 * <p>Limitations to be aware of:
 * <ul>
 *   <li>We track only chunks that have been loaded at least once since
 *       the tracker was created (the manager's loaded-chunks set).
 *       Chunks that unload before status is run still appear in the
 *       set, so the comparison can detect removals when the chunk
 *       reloads.</li>
 *   <li>BlockState identity (the {@code int} stateId) is Minecraft-assigned
 *       at block registration and can change between Minecraft versions.
 *       If that happens the baseline goes stale; re-running /git init
 *       refreshes it.</li>
 *   <li>Block-entity data (chest contents, sign text, …) is currently
 *       treated as a single opaque BlockState. The "modified" category
 *       triggers if the block identity OR any state property changes.</li>
 * </ul>
 */
public final class BlockChangeTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockChangeTracker.class);
    private static final String BASELINE_FILENAME = "baseline.nbt";
    private static final String NBT_KEY_SNAPSHOTS = "snapshots";
    private static final String NBT_KEY_POS = "pos";
    private static final String NBT_KEY_STATE = "state";

    private final ServerLevel level;

    /** Position → stateId at /git init time. Only non-air blocks. */
    private final Map<BlockPos, Integer> baseline = new HashMap<>();

    private BlockChangeTracker(ServerLevel level) {
        this.level = level;
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    /**
     * Capture the baseline for every chunk that has been loaded for this
     * world since the server started, and persist it to disk.
     */
    public static BlockChangeTracker initialize(ServerLevel level) {
        BlockChangeTracker tracker = new BlockChangeTracker(level);
        tracker.captureBaseline();
        tracker.save();
        LOGGER.info("Captured baseline of {} block(s) for {}",
            tracker.baseline.size(), level.dimension());
        return tracker;
    }

    /**
     * Try to restore a tracker for {@code level} from its on-disk baseline.
     * Returns {@code null} if there's no baseline yet.
     */
    public static BlockChangeTracker tryLoad(ServerLevel level) {
        Path path = baselinePath(level);
        if (!Files.exists(path)) {
            return null;
        }
        BlockChangeTracker tracker = new BlockChangeTracker(level);
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            CompoundTag root = NbtIo.read(in);
            ListTag list = root.getList(NBT_KEY_SNAPSHOTS).orElseThrow();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i).orElseThrow();
                BlockPos pos = BlockPos.of(entry.getLong(NBT_KEY_POS).orElseThrow());
                int stateId = entry.getInt(NBT_KEY_STATE).orElseThrow();
                tracker.baseline.put(pos, stateId);
            }
            LOGGER.info("Loaded baseline of {} block(s) for {}",
                tracker.baseline.size(), level.dimension());
        } catch (IOException e) {
            LOGGER.warn("Failed to load baseline from {}: {}", path, e.getMessage(), e);
            return null;
        }
        return tracker;
    }

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    /**
     * Returns true iff {@code /git init} has ever been run for this world
     * (i.e. a baseline exists).
     */
    public boolean hasBaseline() {
        return !baseline.isEmpty();
    }

    public int baselineSize() {
        return baseline.size();
    }

    /**
     * Walk the world's tracked chunks and return deltas vs the baseline.
     * Categorization:
     *
     * <ul>
     *   <li>{@link BlockDelta.Untracked} — a non-air block exists at a
     *       position not in the baseline (newly placed since /git init).</li>
     *   <li>{@link BlockDelta.Modified} — a block exists at a baseline
     *       position but the current BlockState's identity differs.</li>
     *   <li>{@link BlockDelta.Removed} — a baseline position is now air
     *       (no block where there used to be one).</li>
     * </ul>
     */
    public List<BlockDelta> computeDeltas() {
        List<BlockDelta> deltas = new ArrayList<>();
        Set<BlockPos> seenNow = new HashSet<>();

        int minY = level.dimensionType().minY();
        int maxY = minY + level.dimensionType().height();
        int chunkSectionMinY = level.getMinSectionY() * 16;
        int chunkSectionMaxY = (level.getMaxSectionY() + 1) * 16;

        for (ChunkPos chunkPos : BlockChangeTrackerManager.getLoadedChunks(level)) {
            if (!level.getChunkSource().hasChunk(chunkPos.x(), chunkPos.z())) {
                continue;
            }
            ChunkAccess chunk = level.getChunkSource().getChunk(
                chunkPos.x(), chunkPos.z(), ChunkStatus.FULL, false);
            if (chunk == null) continue;

            int minX = chunk.getPos().getMinBlockX();
            int minZ = chunk.getPos().getMinBlockZ();

            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = minY; y < maxY; y++) {
                if (y < chunkSectionMinY || y >= chunkSectionMaxY) continue;
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                if (section == null || section.hasOnlyAir()) continue;
                PalettedContainer<BlockState> palette = section.getStates();
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        pos.set(minX + x, y, minZ + z);
                        BlockState state = palette.get(x, y & 15, z);
                        if (state.isAir()) continue;

                        BlockPos immutable = pos.immutable();
                        seenNow.add(immutable);

                        Integer baselineStateId = baseline.get(immutable);
                        if (baselineStateId == null) {
                            deltas.add(new BlockDelta.Untracked(immutable, state.hashCode()));
                        } else if (baselineStateId != state.hashCode()) {
                            deltas.add(new BlockDelta.Modified(immutable, baselineStateId, state.hashCode()));
                        }
                    }
                }
            }
        }

        // Removed: positions in baseline that are now air (or in unloaded chunks)
        for (Map.Entry<BlockPos, Integer> entry : baseline.entrySet()) {
            BlockPos pos = entry.getKey();
            if (seenNow.contains(pos)) continue;
            ChunkPos containingChunk = ChunkPos.containing(pos);
            if (!level.getChunkSource().hasChunk(containingChunk.x(), containingChunk.z())) continue;

            ChunkAccess chunk = level.getChunkSource().getChunk(
                containingChunk.x(), containingChunk.z(), ChunkStatus.FULL, false);
            if (chunk == null) continue;

            BlockState current = chunk.getBlockState(pos);
            if (!current.isAir()) continue;

            deltas.add(new BlockDelta.Removed(pos, entry.getValue()));
        }

        return deltas;
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    private void captureBaseline() {
        baseline.clear();

        int minY = level.dimensionType().minY();
        int maxY = minY + level.dimensionType().height();
        int chunkSectionMinY = level.getMinSectionY() * 16;
        int chunkSectionMaxY = (level.getMaxSectionY() + 1) * 16;

        for (ChunkPos chunkPos : BlockChangeTrackerManager.getLoadedChunks(level)) {
            if (!level.getChunkSource().hasChunk(chunkPos.x(), chunkPos.z())) continue;
            ChunkAccess chunk = level.getChunkSource().getChunk(
                chunkPos.x(), chunkPos.z(), ChunkStatus.FULL, false);
            if (chunk == null) continue;

            int minX = chunk.getPos().getMinBlockX();
            int minZ = chunk.getPos().getMinBlockZ();

            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = minY; y < maxY; y++) {
                if (y < chunkSectionMinY || y >= chunkSectionMaxY) continue;
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                if (section == null || section.hasOnlyAir()) continue;
                PalettedContainer<BlockState> palette = section.getStates();
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        pos.set(minX + x, y, minZ + z);
                        BlockState state = palette.get(x, y & 15, z);
                        if (state.isAir()) continue;
                        baseline.put(pos.immutable(), state.hashCode());
                    }
                }
            }
        }
    }

    private void save() {
        Path path = baselinePath(level);
        try {
            Files.createDirectories(path.getParent());
            CompoundTag root = new CompoundTag();
            ListTag list = new ListTag();
            for (Map.Entry<BlockPos, Integer> entry : baseline.entrySet()) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.putLong(NBT_KEY_POS, entry.getKey().asLong());
                entryTag.putInt(NBT_KEY_STATE, entry.getValue());
                list.add(entryTag);
            }
            root.put(NBT_KEY_SNAPSHOTS, list);
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
                NbtIo.write(root, out);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save baseline to {}: {}", path, e.getMessage(), e);
        }
    }

    private static Path baselinePath(ServerLevel level) {
        return level.getServer()
            .getWorldPath(LevelResource.LEVEL_DATA_FILE)
            .getParent()
            .resolve("gitmc")
            .resolve(BASELINE_FILENAME);
    }
}
