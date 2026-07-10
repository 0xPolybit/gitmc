package dev.polybit.gitmc.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.polybit.gitmc.block.BlockChangeTracker;
import dev.polybit.gitmc.git.GitManager;
import dev.polybit.gitmc.git.GitManager.AddResult;
import dev.polybit.gitmc.git.GitManager.BranchListResult;
import dev.polybit.gitmc.git.GitManager.CheckoutPlan;
import dev.polybit.gitmc.git.GitManager.CheckoutResult;
import dev.polybit.gitmc.git.GitManager.CommitResult;
import dev.polybit.gitmc.git.GitManager.CreateBranchResult;
import dev.polybit.gitmc.git.GitManager.InitResult;
import dev.polybit.gitmc.git.GitManager.LogEntry;
import dev.polybit.gitmc.git.GitManager.LogResult;
import dev.polybit.gitmc.git.RegionFiles;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands.CommandSelection;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
 *
 * <h2>{@code /git branch} and {@code /git checkout}</h2>
 * {@code /git branch} lists local branches (current one marked), or with a
 * name, creates a new branch at the current commit without switching to it.
 * {@code /git checkout <branch>} switches branches — creating {@code branch}
 * first if it doesn't exist, like {@code git checkout -b}. Because swapping
 * files under a <em>running</em> Minecraft world risks the server's
 * in-memory state autosaving right back over whatever was just checked out
 * (see {@link GitManager} class docs), checkout is two-step:
 * <ul>
 *   <li>{@code /git checkout <branch>} — a dry-run preview: what would
 *       happen, and whether it would require closing the world.</li>
 *   <li>{@code /git checkout <branch> confirm} — actually performs it. Forces
 *       a full save first (so the dirty-check and preview reflect real
 *       on-disk state), refuses if there are uncommitted changes, and — only
 *       if the checkout actually changes file content — halts the server
 *       afterward so the world must be reopened to see the change safely.</li>
 * </ul>
 */
public final class GitMCCommands {

    /** {@code /git log} with no count argument shows this many entries. */
    private static final int DEFAULT_LOG_COUNT = 10;

    /** Upper bound on {@code /git log <count>}, to keep a single chat message reasonable. */
    private static final int MAX_LOG_COUNT = 50;

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
                .then(literal("log")
                    .executes(GitMCCommands::runLogDefault)
                    .then(argument("count", IntegerArgumentType.integer(1, MAX_LOG_COUNT))
                        .executes(GitMCCommands::runLog)))
                .then(literal("branch")
                    .executes(GitMCCommands::runBranchList)
                    .then(argument("name", StringArgumentType.word())
                        .executes(GitMCCommands::runBranchCreate)))
                .then(literal("checkout")
                    .then(argument("branch", StringArgumentType.word())
                        .executes(GitMCCommands::runCheckoutPreview)
                        .then(literal("confirm").executes(GitMCCommands::runCheckoutConfirm))))
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

    /** {@code /git log} (no argument): the {@link #DEFAULT_LOG_COUNT} most recent commits. */
    private static int runLogDefault(CommandContext<CommandSourceStack> ctx) {
        return doLog(ctx, DEFAULT_LOG_COUNT);
    }

    /** {@code /git log <count>}: the {@code count} most recent commits. */
    private static int runLog(CommandContext<CommandSourceStack> ctx) {
        return doLog(ctx, IntegerArgumentType.getInteger(ctx, "count"));
    }

    private static int doLog(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack source = ctx.getSource();
        LogResult result = GitManager.log(worldDir(source.getServer()), count);

        return switch (result) {
            case LogResult.Entries(var entries) -> {
                source.sendSuccess(() -> formatLog(entries), false);
                yield Command.SINGLE_SUCCESS;
            }
            case LogResult.Empty() -> {
                source.sendFailure(Component.literal("No commits yet. Use /git commit first."));
                yield 0;
            }
            case LogResult.Failed(var error) -> {
                source.sendFailure(Component.literal("Failed to read log: " + error));
                yield 0;
            }
        };
    }

    /**
     * Renders a list of {@link LogEntry} as one chat message, newest first,
     * one line per commit: {@code <sha> <message> (<author>, <relative time>)}.
     */
    private static Component formatLog(List<LogEntry> entries) {
        MutableComponent out = Component.empty();
        Instant now = Instant.now();
        for (int i = 0; i < entries.size(); i++) {
            LogEntry entry = entries.get(i);
            if (i > 0) {
                out.append(Component.literal("\n"));
            }
            out.append(Component.literal(entry.shortSha()).withStyle(ChatFormatting.GOLD));
            out.append(Component.literal(" " + entry.message()).withStyle(ChatFormatting.WHITE));
            out.append(Component.literal(
                " (" + entry.authorName() + ", " + relativeTime(entry.timestamp(), now) + ")"
            ).withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    /** Humanizes a duration as "just now" / "N minute(s) ago" / etc., coarsest unit that fits. */
    private static String relativeTime(Instant then, Instant now) {
        long seconds = Math.max(0, Duration.between(then, now).getSeconds());
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return plural(minutes, "minute");
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return plural(hours, "hour");
        }
        long days = hours / 24;
        if (days < 30) {
            return plural(days, "day");
        }
        long months = days / 30;
        if (months < 12) {
            return plural(months, "month");
        }
        return plural(days / 365, "year");
    }

    private static String plural(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s") + " ago";
    }

    /** {@code /git branch} (no argument): lists local branches, current one marked and green. */
    private static int runBranchList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        BranchListResult result = GitManager.listBranches(worldDir(source.getServer()));

        return switch (result) {
            case BranchListResult.Listed(var names, var current) -> {
                source.sendSuccess(() -> formatBranchList(names, current), false);
                yield Command.SINGLE_SUCCESS;
            }
            case BranchListResult.Failed(var error) -> {
                source.sendFailure(Component.literal("Failed to list branches: " + error));
                yield 0;
            }
        };
    }

    /** {@code /git branch <name>}: creates a branch at the current commit without switching to it. */
    private static int runBranchCreate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        CreateBranchResult result = GitManager.createBranch(worldDir(source.getServer()), name);

        return switch (result) {
            case CreateBranchResult.Created(var branchName) -> {
                source.sendSuccess(
                    () -> Component.literal("Created branch '" + branchName + "' at the current commit."), true);
                yield Command.SINGLE_SUCCESS;
            }
            case CreateBranchResult.AlreadyExists(var branchName) -> {
                source.sendFailure(Component.literal("Branch '" + branchName + "' already exists."));
                yield 0;
            }
            case CreateBranchResult.Failed(var error) -> {
                source.sendFailure(Component.literal("Failed to create branch: " + error));
                yield 0;
            }
        };
    }

    private static Component formatBranchList(List<String> names, String current) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(Component.literal("\n"));
            }
            String name = names.get(i);
            boolean isCurrent = name.equals(current);
            String marker = isCurrent ? "* " : "  ";
            out.append(Component.literal(marker + name)
                .withStyle(isCurrent ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        return out;
    }

    /**
     * {@code /git checkout <branch>} (no {@code confirm}): a dry-run preview.
     * Forces a save first so the preview reflects true on-disk state — see
     * {@link GitManager} class docs for why that matters.
     */
    private static int runCheckoutPreview(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        server.saveEverything(false, true, true);

        String branch = StringArgumentType.getString(ctx, "branch");
        CheckoutPlan plan = GitManager.planCheckout(worldDir(server), branch);

        return switch (plan) {
            case CheckoutPlan.Ready(var willCreate, var requiresRestart) -> {
                String action = willCreate
                    ? "Create branch '" + branch + "' at your current commit and switch to it."
                    : "Switch to branch '" + branch + "'.";
                String restartNote = requiresRestart
                    ? " This changes world files, so the world will close afterward — reopen it to see the change."
                    : " This won't change any world files, so you can keep playing right after.";
                source.sendSuccess(() -> Component.literal(
                    action + restartNote + " Run '/git checkout " + branch + " confirm' to proceed."), false);
                yield Command.SINGLE_SUCCESS;
            }
            case CheckoutPlan.AlreadyOnBranch(var b) -> {
                source.sendFailure(Component.literal("Already on branch '" + b + "'."));
                yield 0;
            }
            case CheckoutPlan.Conflicts(var paths) -> {
                source.sendFailure(Component.literal(
                    "Switching to '" + branch + "' would overwrite " + describeConflicts(paths)
                        + " with uncommitted changes. Use /git add and /git commit first, "
                        + "or discard the changes, then try again."));
                yield 0;
            }
            case CheckoutPlan.Failed(var error) -> {
                source.sendFailure(Component.literal("Failed to check branch: " + error));
                yield 0;
            }
        };
    }

    /**
     * {@code /git checkout <branch> confirm}: actually performs the switch.
     * Re-validates everything fresh (no state is carried over from the
     * preview call), so there's no way for a stale preview to authorize an
     * unsafe checkout.
     */
    private static int runCheckoutConfirm(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        server.saveEverything(false, true, true);

        String branch = StringArgumentType.getString(ctx, "branch");
        CheckoutResult result = GitManager.checkout(worldDir(server), branch);

        return switch (result) {
            case CheckoutResult.Switched(var branchName, var requiresRestart) -> {
                if (requiresRestart) {
                    source.sendSuccess(() -> Component.literal(
                        "Switched to '" + branchName + "'. Closing the world now so the change "
                            + "takes effect safely — reopen it to continue."), true);
                    server.halt(false);
                } else {
                    source.sendSuccess(() -> Component.literal(
                        "Switched to '" + branchName + "'. No file changes, so you can keep playing."), true);
                }
                yield Command.SINGLE_SUCCESS;
            }
            case CheckoutResult.AlreadyOnBranch(var b) -> {
                source.sendFailure(Component.literal("Already on branch '" + b + "'."));
                yield 0;
            }
            case CheckoutResult.Conflicts(var paths) -> {
                source.sendFailure(Component.literal(
                    "Switching to '" + branch + "' would overwrite " + describeConflicts(paths)
                        + " with uncommitted changes. Use /git add and /git commit first, "
                        + "or discard the changes, then try again."));
                yield 0;
            }
            case CheckoutResult.Failed(var error) -> {
                source.sendFailure(Component.literal("Failed to checkout: " + error));
                yield 0;
            }
        };
    }

    /** Formats conflicting paths for chat: lists up to a few, then "and N more". */
    private static String describeConflicts(List<String> paths) {
        int maxListed = 5;
        String joined = paths.stream().limit(maxListed).collect(Collectors.joining(", "));
        if (paths.size() > maxListed) {
            joined += ", and " + (paths.size() - maxListed) + " more";
        }
        return paths.size() + " file(s) (" + joined + ")";
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
