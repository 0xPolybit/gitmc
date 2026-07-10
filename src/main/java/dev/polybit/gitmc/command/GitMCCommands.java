package dev.polybit.gitmc.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.polybit.gitmc.block.BlockChangeTracker;
import dev.polybit.gitmc.git.GitManager;
import dev.polybit.gitmc.git.GitManager.AddResult;
import dev.polybit.gitmc.git.GitManager.CommitResult;
import dev.polybit.gitmc.git.GitManager.InitResult;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands.CommandSelection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.File;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Brigadier command tree for the {@code /git} command.
 *
 * <p>Registered via Fabric's {@code CommandRegistrationCallback}, which fires
 * on both dedicated and integrated servers.
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
 *
 * <h2>{@code /git status}</h2>
 * Unlike the other subcommands, {@code status} does not report git's own
 * staged/unstaged/untracked file state. It toggles the in-world block-change
 * overlay tracked by {@link BlockChangeTracker}: {@code /git status} and
 * {@code /git status show} turn the overlay on, {@code /git status hide}
 * turns it off. See {@link BlockChangeTracker} for what is and isn't tracked.
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
            literal("git")
                .then(literal("init").executes(GitMCCommands::runInit))
                .then(literal("status")
                    .executes(ctx -> runStatus(ctx, true))
                    .then(literal("show").executes(ctx -> runStatus(ctx, true)))
                    .then(literal("hide").executes(ctx -> runStatus(ctx, false))))
                .then(literal("add").then(
                    argument("path", StringArgumentType.string())
                        .executes(GitMCCommands::runAdd)))
                .then(literal("commit")
                    .executes(GitMCCommands::runCommitDefault)
                    .then(argument("message", StringArgumentType.string())
                        .executes(GitMCCommands::runCommit)))
        );
    }

    // ---------------------------------------------------------------------
    // Command handlers
    // ---------------------------------------------------------------------

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
     * Toggles the block-change overlay. {@code show} (also the no-argument
     * default) makes it visible; {@code hide} turns it off. Does not touch
     * the underlying tracked-change data — only visibility.
     */
    private static int runStatus(CommandContext<CommandSourceStack> ctx, boolean show) {
        CommandSourceStack source = ctx.getSource();
        BlockChangeTracker tracker = BlockChangeTracker.getInstance();
        tracker.setOverlayVisible(show);

        String message;
        if (show) {
            int count = tracker.totalCount();
            message = count == 0
                ? "Overlay shown. No tracked block changes since the last commit."
                : "Overlay shown. Highlighting " + count + " tracked block change(s).";
        } else {
            message = "Overlay hidden.";
        }
        source.sendSuccess(() -> Component.literal(message), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int runAdd(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String pattern = StringArgumentType.getString(ctx, "path");
        AddResult result = GitManager.add(worldDir(source.getServer()), pattern);

        return switch (result) {
            case AddResult.Added(var count) -> {
                source.sendSuccess(
                    () -> Component.literal("Staged " + count + " file(s) matching '" + pattern + "'."), true);
                yield Command.SINGLE_SUCCESS;
            }
            case AddResult.NothingMatched(var p) -> {
                source.sendFailure(
                    Component.literal("No files matched pattern '" + p + "'."));
                yield 0;
            }
            case AddResult.Failed(var error) -> {
                source.sendFailure(Component.literal("Failed to add: " + error));
                yield 0;
            }
        };
    }

    private static int runCommitDefault(CommandContext<CommandSourceStack> ctx) {
        return doCommit(ctx, defaultCommitMessage(ctx.getSource()));
    }

    private static int runCommit(CommandContext<CommandSourceStack> ctx) {
        return doCommit(ctx, StringArgumentType.getString(ctx, "message"));
    }

    private static int doCommit(CommandContext<CommandSourceStack> ctx, String message) {
        CommandSourceStack source = ctx.getSource();
        CommitResult result = GitManager.commit(
            worldDir(source.getServer()), message, authorFor(source));

        return switch (result) {
            case CommitResult.Created(var sha, var msg) -> {
                // Everything up to this commit is now history — the overlay
                // should go back to showing nothing until the next change.
                BlockChangeTracker.getInstance().clear();
                source.sendSuccess(
                    () -> Component.literal("Created commit " + sha + ": " + msg), true);
                yield Command.SINGLE_SUCCESS;
            }
            case CommitResult.NothingToCommit() -> {
                source.sendFailure(
                    Component.literal("Nothing to commit. Use /git add <path> first."));
                yield 0;
            }
            case CommitResult.Failed(var error) -> {
                source.sendFailure(Component.literal("Failed to commit: " + error));
                yield 0;
            }
        };
    }

    // ---------------------------------------------------------------------
    // Identity helpers
    // ---------------------------------------------------------------------

    /**
     * Default commit message used when the player runs {@code /git commit}
     * without an explicit message.
     */
    private static String defaultCommitMessage(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return "Snapshot by " + player.getName().getString();
        }
        return "Server snapshot";
    }

    /**
     * Build a {@link PersonIdent} for the {@link CommitResult} author/committer
     * from the executing source.
     *
     * <p>For a Minecraft player the identity is
     * {@code <name>.<uuid>@gitmc.invalid} — the {@code .invalid} TLD is
     * non-routable (RFC 2606), so it's safe to use a real-looking address
     * without leaking an actual inbox. For a command-block / console
     * source we fall back to {@code Server <server@gitmc.invalid>}.
     */
    private static PersonIdent authorFor(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            String name = player.getName().getString();
            String uuid = player.getUUID().toString();
            return new PersonIdent(name, name + "." + uuid + "@gitmc.invalid");
        }
        return new PersonIdent("Server", "server@gitmc.invalid");
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
    private static File worldDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent().toFile();
    }
}
