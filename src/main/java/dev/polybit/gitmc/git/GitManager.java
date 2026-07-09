package dev.polybit.gitmc.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Thin wrapper around JGit for the git features this mod exposes in-game.
 *
 * <p>Each method returns a typed {@link InitResult} (a sealed interface) rather
 * than throwing, so command handlers can map outcomes to chat messages without
 * pulling JGit exception types into the command layer.
 */
public final class GitManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitManager.class);

    /**
     * Default ignore rules written into the world's {@code .gitignore} on
     * {@link #init(File)}.
     *
     * <ul>
     *   <li>{@code session.lock} is held by Minecraft while the world is open,
     *       so it changes constantly.</li>
     *   <li>{@code level.dat_old} is regenerated on every save and is
     *       functionally redundant with {@code level.dat}.</li>
     *   <li>{@code logs/} and {@code crash-reports/} contain runtime output,
     *       not world state.</li>
     * </ul>
     */
    static final String DEFAULT_GITIGNORE = """
            # Minecraft session lock — held while the world is loaded.
            session.lock
            # Backup of level.dat, regenerated on every save.
            level.dat_old
            # Runtime logs and crash dumps — not world state.
            logs/
            crash-reports/
            """;

    private GitManager() {
    }

    /**
     * Outcome of {@link #init(File)}.
     *
     * <p>Modelled as a sealed interface so callers use an exhaustive
     * {@code switch} expression and cannot forget a failure branch.
     */
    public sealed interface InitResult {

        /**
         * A new git repository was created at {@link #path()}.
         *
         * @param path                  absolute path of the repository working tree
         * @param wroteDefaultGitignore {@code true} iff this call wrote a
         *                              default {@code .gitignore} into the
         *                              world save; {@code false} if the user
         *                              already had one
         */
        record Created(String path, boolean wroteDefaultGitignore) implements InitResult {}

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
     * and the rest of the save just like any other project. If no
     * {@code .gitignore} is present, a sensible default is written to keep
     * noisy save files ({@code session.lock}, {@code level.dat_old},
     * {@code logs/}, {@code crash-reports/}) out of the repo.
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
            boolean wroteGitignore = writeDefaultGitignoreIfMissing(worldDir);
            return new InitResult.Created(worldDir.getAbsolutePath(), wroteGitignore);
        } catch (GitAPIException | RuntimeException e) {
            // Log to the server log; surface a chat-friendly message via the result.
            String message = describe(e);
            LOGGER.warn("git init failed in {}: {}", worldDir, message, e);
            return new InitResult.Failed(message);
        }
    }

    /**
     * Writes {@link #DEFAULT_GITIGNORE} into {@code worldDir} if no
     * {@code .gitignore} already exists. Returns {@code true} iff this call
     * actually wrote the file; an existing user-authored {@code .gitignore}
     * is left untouched.
     */
    private static boolean writeDefaultGitignoreIfMissing(File worldDir) {
        File gitignore = new File(worldDir, ".gitignore");
        if (gitignore.isFile()) {
            return false;
        }
        try {
            Files.writeString(gitignore.toPath(), DEFAULT_GITIGNORE, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not write default .gitignore in {}: {}", worldDir, describe(e), e);
            return false;
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