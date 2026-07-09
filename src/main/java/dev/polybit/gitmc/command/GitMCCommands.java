package dev.polybit.gitmc.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.polybit.gitmc.git.GitManager;
import dev.polybit.gitmc.git.GitManager.InitResult;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.Commands.CommandSelection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import static net.minecraft.commands.Commands.literal;

/**
 * Brigadier command tree for the {@code /gitmc} command.
 *
 * <p>The root is registered via Fabric's {@code CommandRegistrationCallback},
 * which fires on both dedicated and integrated servers. World-state mutations
 * (commands that would take a snapshot of the save) are deliberately out of
 * scope for the skeleton — this only wires up {@code init}.
 *
 * <h2>Minecraft 26.2 API mapping</h2>
 * <ul>
 *   <li>{@code net.minecraft.command.*} → {@code net.minecraft.commands.*} (package renamed)</li>
 *   <li>{@code ServerCommandSource} → {@code CommandSourceStack}</li>
 *   <li>{@code CommandRegistryAccess} → {@code CommandBuildContext}</li>
 *   <li>{@code ServerCommandSource.Environment} → {@code Commands.CommandSelection}</li>
 *   <li>{@code Text.literal(...)} → {@code Component.literal(...)}</li>
 *   <li>{@code MinecraftServer.getSavePath(WorldSavePath.ROOT)} →
 *       {@code MinecraftServer.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent()}</li>
 * </ul>
 */
public final class GitMCCommands {

    private GitMCCommands() {
    }

    public static void register(
        CommandDispatcher<CommandSourceStack> dispatcher,
        CommandBuildContext registryAccess,
        CommandSelection environment
    ) {
        dispatcher.register(
            literal("gitmc")
                .then(literal("init").executes(GitMCCommands::runInit))
        );
    }

    private static int runInit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        InitResult result = GitManager.init(worldDir(source.getServer()));

        return switch (result) {
            case InitResult.Created(var path, var wroteDefaultGitignore) -> {
                String message = wroteDefaultGitignore
                    ? "Initialized git repository in " + path + "; wrote default .gitignore"
                    : "Initialized git repository in " + path;
                source.sendSuccess(() -> Component.literal(message), true);
                yield Command.SINGLE_SUCCESS;
            }
            case InitResult.AlreadyExists(var path) -> {
                source.sendSuccess(
                    () -> Component.literal("Already a git repository: " + path), true);
                yield Command.SINGLE_SUCCESS;
            }
            case InitResult.Failed(var error) -> {
                source.sendFailure(
                    Component.literal("Failed to initialize git repository: " + error));
                yield 0;
            }
        };
    }

    /**
     * Resolves the world's save root directory.
     *
     * <p>In 26.2, {@code LevelResource.LEVEL_DATA_FILE} maps to
     * {@code <worldRoot>/level.dat}; the directory containing that file is
     * the world save root. {@code MinecraftServer.storageSource} is
     * protected, so {@link MinecraftServer#getWorldPath(LevelResource)} is
     * the only public surface that reaches the storage backend.
     */
    private static java.io.File worldDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent().toFile();
    }
}
