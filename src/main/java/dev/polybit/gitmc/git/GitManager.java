package dev.polybit.gitmc.git;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Set;

/**
 * Thin wrapper around JGit for the git features this mod exposes in-game.
 *
 * <p>Each method returns a typed sealed-interface result rather than throwing,
 * so command handlers can map outcomes to chat messages without pulling JGit
 * exception types into the command layer.
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

    // ---------------------------------------------------------------------
    // Result types
    // ---------------------------------------------------------------------

    /** Outcome of {@link #init(File)}. */
    public sealed interface InitResult {
        record Created(String path, boolean wroteDefaultGitignore) implements InitResult {}
        record AlreadyExists(String path) implements InitResult {}
        record Failed(String error) implements InitResult {}
    }

    /** Outcome of {@link #add(File, String)}. */
    public sealed interface AddResult {
        record Added(int count) implements AddResult {}
        record NothingMatched(String pattern) implements AddResult {}
        record Failed(String error) implements AddResult {}
    }

    /** Outcome of {@link #commit(File, String, PersonIdent)}. */
    public sealed interface CommitResult {
        record Created(String shortSha, String message) implements CommitResult {}
        record NothingToCommit() implements CommitResult {}
        record Failed(String error) implements CommitResult {}
    }

    // ---------------------------------------------------------------------
    // Operations
    // ---------------------------------------------------------------------

    /**
     * Initialize a git repository in {@code worldDir}, or report that one
     * already exists. See class-level docs for {@link #DEFAULT_GITIGNORE}
     * for what's added on a fresh init.
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
            String message = describe(e);
            LOGGER.warn("git init failed in {}: {}", worldDir, message, e);
            return new InitResult.Failed(message);
        }
    }

    /**
     * Stages files in the repository at {@code worldDir} matching the given
     * JGit file pattern (e.g. {@code "."}, {@code "*"}, {@code "region/"},
     * or a specific path). Patterns that match no files return
     * {@link AddResult.NothingMatched} rather than failing.
     */
    public static AddResult add(File worldDir, String pattern) {
        if (!isRepo(worldDir)) {
            return new AddResult.Failed("Not a git repository. Run /git init first.");
        }
        if (pattern == null || pattern.isBlank()) {
            return new AddResult.Failed("Path pattern cannot be empty.");
        }
        try (Git git = Git.open(worldDir)) {
            int before = stagedFileCount(git);
            // JGit 6.x's AddCommand.call() returns DirCache, not a file list, so
            // we count the index delta to figure out how many files were staged.
            git.add().addFilepattern(pattern).call();
            int after = stagedFileCount(git);
            int added = after - before;
            if (added <= 0) {
                return new AddResult.NothingMatched(pattern);
            }
            return new AddResult.Added(added);
        } catch (IOException | GitAPIException | RuntimeException e) {
            String failure = describe(e);
            LOGGER.warn("git add failed in {} (pattern={}): {}", worldDir, pattern, failure, e);
            return new AddResult.Failed(failure);
        }
    }

    /**
     * Stages exactly the given repository-relative file paths — used by the
     * coordinate-based {@code /git add} variants, where {@link RegionFiles}
     * has already resolved precisely which region/entities/poi files exist
     * on disk for the requested position or range. Unlike {@link #add(File, String)},
     * this isn't a glob pattern; each entry is staged as a literal path.
     */
    public static AddResult addPaths(File worldDir, Collection<String> relativePaths) {
        if (!isRepo(worldDir)) {
            return new AddResult.Failed("Not a git repository. Run /git init first.");
        }
        if (relativePaths.isEmpty()) {
            return new AddResult.NothingMatched("no region files at those coordinates");
        }
        try (Git git = Git.open(worldDir)) {
            int before = stagedFileCount(git);
            AddCommand add = git.add();
            for (String path : relativePaths) {
                add.addFilepattern(path);
            }
            add.call();
            int after = stagedFileCount(git);
            int added = after - before;
            if (added <= 0) {
                return new AddResult.NothingMatched("already staged or unchanged");
            }
            return new AddResult.Added(added);
        } catch (IOException | GitAPIException | RuntimeException e) {
            String failure = describe(e);
            LOGGER.warn("git add (paths={}) failed in {}: {}", relativePaths, worldDir, failure, e);
            return new AddResult.Failed(failure);
        }
    }

    /**
     * Creates a commit with {@code message} and the given author (also used
     * as the committer) from whatever is currently staged in the repository
     * at {@code worldDir}. Returns {@link CommitResult.NothingToCommit} if
     * the index has no changes relative to HEAD.
     */
    public static CommitResult commit(File worldDir, String message, PersonIdent author) {
        if (!isRepo(worldDir)) {
            return new CommitResult.Failed("Not a git repository. Run /git init first.");
        }
        if (message == null || message.isBlank()) {
            return new CommitResult.Failed("Commit message cannot be empty.");
        }
        try (Git git = Git.open(worldDir)) {
            Status status = git.status().call();
            Set<String> added = status.getAdded();
            Set<String> changed = status.getChanged();
            Set<String> removed = status.getRemoved();
            if (added.isEmpty() && changed.isEmpty() && removed.isEmpty()) {
                return new CommitResult.NothingToCommit();
            }
            RevCommit commit = git.commit()
                .setMessage(message)
                .setAuthor(author)
                .setCommitter(author)
                .call();
            String sha = commit.getName();
            return new CommitResult.Created(sha.substring(0, Math.min(7, sha.length())), message);
        } catch (IOException | GitAPIException | RuntimeException e) {
            String failure = describe(e);
            LOGGER.warn("git commit failed in {}: {}", worldDir, failure, e);
            return new CommitResult.Failed(failure);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static boolean isRepo(File worldDir) {
        return worldDir != null && worldDir.isDirectory()
            && new File(worldDir, ".git").isDirectory();
    }

    /**
     * Total number of paths that differ between the index and HEAD — used
     * by {@link #add(File, String)} to compute the index delta.
     */
    private static int stagedFileCount(Git git) throws GitAPIException {
        Status s = git.status().call();
        return s.getAdded().size() + s.getChanged().size() + s.getRemoved().size();
    }

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

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return t.getClass().getSimpleName();
    }
}
