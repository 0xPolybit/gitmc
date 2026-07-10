package dev.polybit.gitmc.git;

import dev.polybit.gitmc.mixin.MinecraftServerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves block coordinates to the on-disk region-partitioned files
 * ({@code region/}, {@code entities/}, {@code poi/}) that cover them, so
 * {@code /git add} can stage "the area I built in" without the player
 * needing to know Minecraft's region-file naming scheme.
 *
 * <p>Region files partition the world in the X/Z plane only — 512×512
 * blocks (32×32 chunks) per file, covering a whole chunk column top to
 * bottom regardless of Y. So a coordinate's Y value never changes which
 * file it falls into; a range's Y bounds are accepted for a natural 3D
 * input, but only the X/Z bounds are used to select files.
 */
public final class RegionFiles {

    private static final String[] PARTITIONS = {"region", "entities", "poi"};

    /** Blocks per region file on each horizontal axis (32 chunks × 16 blocks). */
    private static final int BLOCKS_PER_REGION = 512;

    /** log2(BLOCKS_PER_REGION); block coordinate >> this = region coordinate. */
    private static final int REGION_SHIFT = 9;

    private RegionFiles() {
    }

    /** Resolves the region file(s) covering a single block position. */
    public static Set<String> resolve(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos) {
        return resolve(server, dimension, pos, pos);
    }

    /**
     * Resolves the region file(s) covering every block position in the
     * inclusive box between {@code from} and {@code to} (order doesn't
     * matter — min/max are taken per axis). Returns paths relative to the
     * world save root, using forward slashes, for every region/entities/poi
     * file that both falls within the box's X/Z span and actually exists
     * on disk. Files that were never generated (e.g. an unexplored area)
     * are simply absent from the result, not an error.
     */
    public static Set<String> resolve(MinecraftServer server, ResourceKey<Level> dimension, BlockPos from, BlockPos to) {
        LevelStorageSource.LevelStorageAccess storage = ((MinecraftServerAccessor) server).gitmc$storageSource();
        Path dimensionPath = storage.getDimensionPath(dimension);
        Path worldRoot = storage.getLevelPath(net.minecraft.world.level.storage.LevelResource.LEVEL_DATA_FILE).getParent();

        int minRegionX = Math.min(from.getX(), to.getX()) >> REGION_SHIFT;
        int maxRegionX = Math.max(from.getX(), to.getX()) >> REGION_SHIFT;
        int minRegionZ = Math.min(from.getZ(), to.getZ()) >> REGION_SHIFT;
        int maxRegionZ = Math.max(from.getZ(), to.getZ()) >> REGION_SHIFT;

        Set<String> patterns = new LinkedHashSet<>();
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                String fileName = "r." + rx + "." + rz + ".mca";
                for (String partition : PARTITIONS) {
                    Path candidate = dimensionPath.resolve(partition).resolve(fileName);
                    if (!Files.isRegularFile(candidate)) {
                        continue;
                    }
                    Path relative = worldRoot.relativize(candidate);
                    if (relative.startsWith("..")) {
                        continue; // outside the repo root; shouldn't happen, but don't stage it if it does.
                    }
                    patterns.add(relative.toString().replace('\\', '/'));
                }
            }
        }
        return patterns;
    }
}
