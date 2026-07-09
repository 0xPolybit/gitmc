package dev.polybit.gitmc;

import dev.polybit.gitmc.block.BlockChangeTrackerManager;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric entry point for GitMC.
 *
 * <p>GitMC provides block-level version control for Minecraft worlds: a
 * player can snapshot the state of their world's loaded chunks with
 * {@code /git init}, see what's changed since via {@code /git status
 * [show|hide]}, and reset with a future {@code /git reset} command.
 *
 * <p>The tracker is per-world, persisted to {@code <world>/gitmc/baseline.nbt}
 * on init, and rehydrated when the world loads.
 */
public final class GitMC implements ModInitializer {

    /** Mod id; also the logger name and the (former) root command name. */
    public static final String MOD_ID = "gitmc";

    /**
     * Mirrors {@code gradle.properties}. The {@code fabric.mod.json} baked
     * into the jar is the authoritative source of truth; this constant
     * exists for log messages.
     */
    public static final String VERSION = "0.1.0";

    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing {} v{}", MOD_ID, VERSION);
        BlockChangeTrackerManager.register();
    }
}
