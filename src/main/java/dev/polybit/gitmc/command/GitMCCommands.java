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
import dev.polybit.gitmc.git.RegionFiles;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands.CommandSelection;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.File;
import java.util.Set;

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
 * <h2>{@code /git add}</h2>
 * In addition to a JGit file pattern, {@code /git add} accepts block
 * coordinates: a single position, or a {@code from}/{@code to} range (same
 * shape as vanilla {@code /fill}, including {@code ~}-relative coordinates).
 * Coordinates are resolved to the region/entities/poi files that actually
 * exist on disk for that area — see {@link RegionFiles} — so a player can
 * stage "what I built over there" without knowing Minecraft's region-file
 * naming scheme. Coordinates are relative to the executing source's current
 * dimension ({@link CommandSourceStack#getLevel()}), same as vanilla
 * world-editing commands.
 *
 * <h2>{@code /git status}</h2>
 * Unlike the other subcommands, {@code status} does not report git's own
 * staged/unstaged/untracked file state. It controls the in-world
 * block-change overlay tracked by {@link BlockChangeTracker}, with three
 * distinct behaviors:
 * <ul>
 *   <li>{@code /git status show} — shown until explicitly hidden.</li>
 *   <li>{@code /git status} (no argument) — shown at full opacity for
 *       {@link BlockChangeTracker#TIMED_VISIBLE_MILLIS}, then fades out
 *       over {@link BlockChangeTracker#TIMED_FADE_MILLIS}.</li>
 *   <li>{@code /git status hide} — hidden immediately, whether it was
 *       previously persistent or mid-countdown.</li>
 * </ul>
 * See {@link BlockChangeTracker} for what is and isn't tracked.
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
                    .executes(GitMCCommands::runStatusTimed)
                    .then(literal("show").executes(GitMCCommands::runStatusShow))
                    .then(literal("hide").executes(GitMCCommands::runStatusHide)))
                .then(literal("add")
                    .then(argument("path", StringArgumentType.string())
                        .executes(GitMCCommands::runAdd))
                    .then(argument("pos", BlockPosArgument.blockPos())
                        .executes(GitMCCommands::runAddPos))
                    .then(argument("from", BlockPosArgument.blockPos())
                        .then(argument("to", BlockPosArgument.blockPos())
                            .executes(GitMCCommands::runAddRange))))
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

    /** {@code /git status} (no argument): shown for a fixed window, then auto-fades. */
    private static int runStatusTimed(CommandContext<CommandSourceStack> ctx) {
        BlockChangeTracker tracker = BlockChangeTracker.getInstance();
        tracker.showTimed();
        long seconds = BlockChangeTracker.TIMED_VISIBLE_MILLIS / 1000L;
        reportOverlayShown(ctx.getSource(), tracker, "Overlay shown for " + seconds + " seconds.");
        return Command.SINGLE_SUCCESS;
    }

    /** {@code /git status show}: stays visible until {@code /git status hide}. */
    private static int runStatusShow(CommandContext<CommandSourceStack> ctx) {
        BlockChangeTracker tracker = BlockChangeTracker.getInstance();
        tracker.showPersistent();
        reportOverlayShown(ctx.getSource(), tracker, "Overlay shown.");
        return Command.SINGLE_SUCCESS;
    }

    /** {@code /git status hide}: hides immediately, canceling any active timer. */
    private static int runStatusHide(CommandContext<CommandSourceStack> ctx) {
        BlockChangeTracker.getInstance().hide();
        ctx.getSource().sendSuccess(() -> Component.literal("Overlay hidden."), false);
        return Command.SINGLE_SUCCESS;
    }

    private static void reportOverlayShown(CommandSourceStack source, BlockChangeTracker tracker, String prefix) {
        int count = tracker.totalCount();
        String message = count == 0
            ? prefix + " No tracked block changes since the last commit."
            : prefix + " Highlighting " + count + " tracked block change(s).";
        source.sendSuccess(() -> Component.literal(message), false);
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

    /** {@code /git add <pos>}: stage the region file(s) covering a single block position. */
    private static int runAddPos(CommandContext<CommandSourceStack> ctx) {
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        return doAddRegion(ctx.getSource(), pos, pos);
    }

    /** {@code /git add <from> <to>}: stage the region file(s) covering a coordinate range. */
    private static int runAddRange(CommandContext<CommandSourceStack> ctx) {
        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
        return doAddRegion(ctx.getSource(), from, to);
    }

    private static int doAddRegion(CommandSourceStack source, BlockPos from, BlockPos to) {
        MinecraftServer server = source.getServer();
        ResourceKey<Level> dimension = source.getLevel().dimension();
        Set<String> patterns = RegionFiles.resolve(server, dimension, from, to);
        AddResult result = GitManager.addPaths(worldDir(server), patterns);
        String range = describeRange(from, to);

        return switch (result) {
            case AddResult.Added(var count) -> {
                source.sendSuccess(
                    () -> Component.literal("Staged " + count + " file(s) covering " + range + "."), true);
                yield Command.SINGLE_SUCCESS;
            }
            case AddResult.NothingMatched(var ignored) -> {
                source.sendFailure(Component.literal("No files found for " + range + "."));
                yield 0;
            }
            case AddResult.Failed(var error) -> {
                source.sendFailure(Component.literal("Failed to add: " + error));
                yield 0;
            }
        };
    }

    /** Formats a position or range for chat feedback: {@code (x, y, z)} or {@code (x, y, z) to (x, y, z)}. */
    private static String describeRange(BlockPos from, BlockPos to) {
        String fromStr = "(" + from.getX() + ", " + from.getY() + ", " + from.getZ() + ")";
        if (from.equals(to)) {
            return fromStr;
        }
        String toStr = "(" + to.getX() + ", " + to.getY() + ", " + to.getZ() + ")";
        return fromStr + " to " + toStr;
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
