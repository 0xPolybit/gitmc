package dev.polybit.gitmc;

import dev.polybit.gitmc.command.GitMCCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric entry point for GitMC.
 *
 * <p>GitMC brings git-style version control to Minecraft worlds: a player can
 * initialize a git repository inside the currently loaded world's save
 * directory and snapshot progress with commits, branch off experiments, merge
 * changes back together, and revert when a build goes sideways — all from
 * in-game commands.
 *
 * <p>This class wires up the {@link ModInitializer} hook and registers the
 * {@code /gitmc} command tree. The actual git operations are encapsulated in
 * {@link dev.polybit.gitmc.git.GitManager}.
 */
public final class GitMC implements ModInitializer {

    /** Mod id; also the logger name and the root command name. */
    public static final String MOD_ID = "gitmc";

    /**
     * Human-readable version string.
     *
     * <p>Mirrored from {@code gradle.properties}. The {@code fabric.mod.json}
     * baked into the jar is the authoritative source of truth — this constant
     * exists for log messages and for callers that want to display the
     * running version. Bump both together.
     */
    public static final String VERSION = "0.1.0";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing {} v{}", MOD_ID, VERSION);
        CommandRegistrationCallback.EVENT.register(GitMCCommands::register);
    }
}
