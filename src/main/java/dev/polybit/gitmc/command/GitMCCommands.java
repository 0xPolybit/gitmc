package dev.polybit.gitmc.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.polybit.gitmc.block.BlockChangeTracker;
import dev.polybit.gitmc.block.BlockChangeTrackerManager;
import dev.polybit.gitmc.block.BlockDelta;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands.CommandSelection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

import static net.minecraft.commands.Commands.literal;

/**
 * Brigadier command tree for the {@code /git} command.
 *
 * <p>Registered via Fabric's {@code CommandRegistrationCallback}, which fires
 * on both dedicated and integrated servers.
 *
 * <h2>Block-level design</h2>
 * <p>GitMC does not use JGit. The {@code /git} command family operates on
 * an in-memory + on-disk {@link BlockChangeTracker} per world:
 *
 * <ul>
 *   <li>{@code /git init} — capture every currently-loaded block as the
 *       baseline (overwrites any prior baseline).</li>
 *   <li>{@code /git status} — show deltas vs baseline, auto-fading after
 *       30 seconds.</li>
 *   <li>{@code /git status show} — same as {@code status}, but persistent
 *       until {@code /git status hide} is run.</li>
 *   <li>{@code /git status hide} — clear highlights for the executor.</li>
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
            literal("git")
                .then(literal("init").executes(GitMCCommands::runInit))
                .then(literal("status")
                    .executes(GitMCCommands::runStatus)
                    .then(literal("show").executes(GitMCCommands::runStatusShow))
                    .then(literal("hide").executes(GitMCCommands::runStatusHide)))
        );
    }

    // ---------------------------------------------------------------------
    // Handlers
    // ---------------------------------------------------------------------

    private static int runInit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockChangeTracker tracker = BlockChangeTrackerManager.initialize(level);
        source.sendSuccess(() -> Component.literal(
            "Captured baseline of " + tracker.baselineSize() + " block(s). Use /git status to see changes."
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int runStatus(CommandContext<CommandSourceStack> ctx) {
        return doStatus(ctx, /*persistent*/ false);
    }

    private static int runStatusShow(CommandContext<CommandSourceStack> ctx) {
        return doStatus(ctx, /*persistent*/ true);
    }

    private static int runStatusHide(CommandContext<CommandSourceStack> ctx) {
        // Phase B (client rendering) will hide the visual highlights here.
        // For now we just acknowledge the command.
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Highlights cleared. (Visual fade-in comes in Phase B.)"
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int doStatus(CommandContext<CommandSourceStack> ctx, boolean persistent) {
        CommandSourceStack source = ctx.getSource();
        BlockChangeTracker tracker = BlockChangeTrackerManager.get(source.getLevel());

        if (tracker == null) {
            source.sendFailure(Component.literal(
                "No baseline yet. Run /git init first."
            ));
            return 0;
        }

        List<BlockDelta> deltas = tracker.computeDeltas();
        if (deltas.isEmpty()) {
            source.sendSuccess(
                () -> Component.literal("Working tree clean (no block deltas)."), false);
            return Command.SINGLE_SUCCESS;
        }

        // Count each category; we use an effectively-final-friendly accumulator
        // (arrays) so the captured values can be used inside the lambda.
        int[] counts = new int[3]; // [untracked, modified, removed]
        for (BlockDelta d : deltas) {
            switch (d) {
                case BlockDelta.Untracked ignored -> counts[0]++;
                case BlockDelta.Modified ignored -> counts[1]++;
                case BlockDelta.Removed ignored -> counts[2]++;
            }
        }

        int untracked = counts[0];
        int modified = counts[1];
        int removed = counts[2];
        int total = deltas.size();

        source.sendSuccess(
            () -> Component.literal(
                persistent
                    ? "Highlighting " + total + " change(s) (persistent): "
                    : "Highlighting " + total + " change(s) (auto-fades in 30s): "
            )
            .append(summarize(untracked, modified, removed))
            .append(Component.literal(
                " (In-world rendering is delivered by the Phase B client mod.)"
            )),
            false);
        return Command.SINGLE_SUCCESS;
    }

    private static MutableComponent summarize(int untracked, int modified, int removed) {
        MutableComponent out = Component.empty();
        if (untracked > 0) {
            out.append(Component.literal(untracked + " new "));
        }
        if (modified > 0) {
            if (!out.getString().isEmpty()) out.append(Component.literal(" / "));
            out.append(Component.literal(modified + " modified "));
        }
        if (removed > 0) {
            if (!out.getString().isEmpty()) out.append(Component.literal(" / "));
            out.append(Component.literal(removed + " removed "));
        }
        return out;
    }
}
