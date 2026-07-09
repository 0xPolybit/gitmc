package dev.polybit.gitmc.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Thin wrapper around JGit for the git features this mod exposes in-game.
 *
 * <p>Each method returns a typed {@link InitResult} (a sealed interface) rather
 * than throwing, so command handlers can map outcomes to chat messages without
 * pulling JGit exception types into the command layer.
 */
public final class GitManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitManager.class);

    private GitManager() {
    }

    /**
     * Outcome of {@link #init(File)}.
     *
     * <p>Modelled as a sealed interface so callers use an exhaustive
     * {@code switch} expression and cannot forget a failure branch.
     */
    public sealed interface InitResult {

        /** A new git repository was created at {@link #path()}. */
        record Created(String path) implements InitResult {}

        /** A git repository already existed at {@link #path()}. */
        record AlreadyExists(String path) implements InitResult {}

        /** Initialization failed; {@link #error()} is a human-readable cause. */
        record Failed(String error) implements InitResult {}
    }

    /**
     * Initialize a git repository in {@code worldDir}, or report that one
     * already exists.
     *
     * <p>The {@code .git} directory is created at the top of the world's save
     * folder so players can commit region files, {@code level.dat}, datapacks,
     * and the rest of the save just like any other project.
     *
     * @param worldDir the world's save directory; must exist and be a directory
     * @return {@link InitResult.Created} on success, {@link InitResult.AlreadyExists}
     *         if {@code .git} is already present, or {@link InitResult.Failed} on error.
     */
    public static InitResult init(File worldDir) {
        if (worldDir == null || !worldDir.isDirectory()) {
            return new InitResult.Failed("world directory does not exist: " + worldDir);
        }

        File gitDir = new File(worldDir, ".git");
        if (gitDir.isDirectory()) {
            return new InitResult.AlreadyExists(worldDir.getAbsolutePath());
        }

        try (Git ignored = Git.init().setDirectory(worldDir).call()) {
            return new InitResult.Created(worldDir.getAbsolutePath());
        } catch (GitAPIException | RuntimeException e) {
            // Log to the server log; surface a chat-friendly message via the result.
            String message = describe(e);
            LOGGER.warn("git init failed in {}: {}", worldDir, message, e);
            return new InitResult.Failed(message);
        }
    }

    /**
     * Best-effort human-readable description of an exception. Falls back to the
     * exception class name when the message is null or blank.
     */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return t.getClass().getSimpleName();
    }
}
