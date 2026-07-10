package dev.polybit.gitmc.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code MinecraftServer.storageSource} (protected) so
 * {@link dev.polybit.gitmc.git.RegionFiles} can resolve a dimension's
 * on-disk folder via {@code LevelStorageAccess.getDimensionPath} — the same
 * resolution Minecraft itself uses, rather than reimplementing the
 * DIM-1/DIM1/custom-dimension folder convention by hand.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess gitmc$storageSource();
}
