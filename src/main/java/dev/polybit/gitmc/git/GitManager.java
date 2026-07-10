package dev.polybit.gitmc.git;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Thin wrapper around JGit for the git features this mod exposes in-game.
 *
 * <p>Each method returns a typed sealed-interface result rather than throwing,
 * so command handlers can map outcomes to chat messages without pulling JGit
 * exception types into the command layer.
 *
 * <h2>Why {@code checkout} is more dangerous here than in a normal repo</h2>
 * A plain git checkout just rewrites files that nothing else has open. A
 * running Minecraft world isn't like that: the server keeps loaded chunks,
 * player data, and other state in memory, and periodically autosaves that
 * in-memory state back to disk. If a checkout swaps the on-disk files out
 * from under a still-running world, the server's stale in-memory state can
 * autosave right back over whatever was just checked out — silently undoing
 * it. This class stays Minecraft-agnostic (it only knows about files and
 * JGit), so the caller (see {@code GitMCCommands}) is responsible for
 * forcing a full save before checkout and, when the checkout actually
 * changes file content ({@link CheckoutPlan.Ready#requiresWorldRestart()}
 * / {@link CheckoutResult.Switched#worldRestartRequired()}), stopping the
 * server afterward so stale state can never be written back.
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

    /** Outcome of {@link #log(File, int)}. */
    public sealed interface LogResult {
        record Entries(List<LogEntry> entries) implements LogResult {}
        /** No commits yet — distinct from {@link Failed} since it isn't an error. */
        record Empty() implements LogResult {}
        record Failed(String error) implements LogResult {}
    }

    /** One commit's summary, as shown by {@code /git log}. */
    public record LogEntry(String shortSha, String message, String authorName, Instant timestamp) {}

    /** Outcome of {@link #listBranches(File)}. */
    public sealed interface BranchListResult {
        record Listed(List<String> names, String current) implements BranchListResult {}
        record Failed(String error) implements BranchListResult {}
    }

    /** Outcome of {@link #createBranch(File, String)}. */
    public sealed interface CreateBranchResult {
        record Created(String name) implements CreateBranchResult {}
        record AlreadyExists(String name) implements CreateBranchResult {}
        record Failed(String error) implements CreateBranchResult {}
    }

    /**
     * Outcome of {@link #planCheckout(File, String)} — computed without
     * mutating anything, so a caller can preview what a checkout would do
     * (and require explicit confirmation) before actually performing one.
     * Shares its cases with {@link CheckoutResult} by design: both are
     * answers to "what would/did checking out to this branch do".
     */
    public sealed interface CheckoutPlan {
        /**
         * @param branchExists         false means checkout will create {@code branch} at the current commit
         * @param requiresWorldRestart true if the target's file content differs from what's
         *                             on disk right now — see the class-level docs for why
         *                             that matters for a running Minecraft world
         */
        record Ready(boolean branchExists, boolean requiresWorldRestart) implements CheckoutPlan {}
        record AlreadyOnBranch(String branch) implements CheckoutPlan {}
        /**
         * At least one path both differs between the current and target
         * branch, and is currently uncommitted in the working tree — an
         * actual conflict, not just "something somewhere is dirty". See
         * {@link #findConflictingPaths} for why this distinction matters.
         */
        record Conflicts(List<String> paths) implements CheckoutPlan {}
        record Failed(String error) implements CheckoutPlan {}
    }

    /** Outcome of {@link #checkout(File, String)}. */
    public sealed interface CheckoutResult {
        record Switched(String branch, boolean worldRestartRequired) implements CheckoutResult {}
        record AlreadyOnBranch(String branch) implements CheckoutResult {}
        /** See {@link CheckoutPlan.Conflicts}. */
        record Conflicts(List<String> paths) implements CheckoutResult {}
        record Failed(String error) implements CheckoutResult {}
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

    /**
     * Returns the {@code maxCount} most recent commits, newest first. An
     * empty repository (no commits yet) is {@link LogResult.Empty}, not a
     * failure — JGit itself signals this by throwing {@link NoHeadException}
     * from {@code LogCommand.call()}, which we translate here so callers
     * never need to know that.
     */
    public static LogResult log(File worldDir, int maxCount) {
        if (!isRepo(worldDir)) {
            return new LogResult.Failed("Not a git repository. Run /git init first.");
        }
        try (Git git = Git.open(worldDir)) {
            List<LogEntry> entries = new ArrayList<>();
            for (RevCommit commit : git.log().setMaxCount(maxCount).call()) {
                PersonIdent author = commit.getAuthorIdent();
                String sha = commit.getName();
                entries.add(new LogEntry(
                    sha.substring(0, Math.min(7, sha.length())),
                    commit.getShortMessage(),
                    author.getName(),
                    author.getWhenAsInstant()
                ));
            }
            return entries.isEmpty() ? new LogResult.Empty() : new LogResult.Entries(entries);
        } catch (NoHeadException e) {
            return new LogResult.Empty();
        } catch (IOException | GitAPIException | RuntimeException e) {
            String failure = describe(e);
            LOGGER.warn("git log failed in {}: {}", worldDir, failure, e);
            return new LogResult.Failed(failure);
        }
    }

    /** Lists all local branches, alphabetically, along with which one is current. */
    public static BranchListResult listBranches(File worldDir) {
        if (!isRepo(worldDir)) {
            return new BranchListResult.Failed("Not a git repository. Run /git init first.");
        }
        try (Git git = Git.open(worldDir)) {
            String current = git.getRepository().getBranch();
            List<String> names = git.branchList().call().stream()
                .map(ref -> shortBranchName(ref.getName()))
                .sorted()
                .toList();
            return new BranchListResult.Listed(names, current);
        } catch (IOException | GitAPIException | RuntimeException e) {
            String failure = describe(e);
            LOGGER.warn("git branch (list) failed in {}: {}", worldDir, failure, e);
            return new BranchListResult.Failed(failure);
        }
    }

    /** Creates a new branch at the current commit, without switching to it. */
    public static CreateBranchResult createBranch(File worldDir, String name) {
        if (!isRepo(worldDir)) {
            return new CreateBranchResult.Failed("Not a git repository. Run /git init first.");
        }
        if (name == null || name.isBlank()) {
            return new CreateBranchResult.Failed("Branch name cannot be empty.");
        }
        try (Git git = Git.open(worldDir)) {
            git.branchCreate().setName(name).call();
            return new CreateBranchResult.Created(name);
        } catch (RefAlreadyExistsException e) {
            return new CreateBranchResult.AlreadyExists(name);
        } catch (IOException | GitAPIException | RuntimeException e) {
            String failure = describe(e);
            LOGGER.warn("git branch (create={}) failed in {}: {}", name, worldDir, failure, e);
            return new CreateBranchResult.Failed(failure);
        }
    }

    /**
     * Computes what checking out to {@code branchName} would do, without
     * changing anything. See {@link CheckoutPlan} for the possible outcomes.
     */
    public static CheckoutPlan planCheckout(File worldDir, String branchName) {
        if (!isRepo(worldDir)) {
            return new CheckoutPlan.Failed("Not a git repository. Run /git init first.");
        }
        if (branchName == null || branchName.isBlank()) {
            return new CheckoutPlan.Failed("Branch name cannot be empty.");
        }
        try (Git git = Git.open(worldDir)) {
            CheckoutPreflight preflight = computePreflight(git, branchName);
            if (preflight.currentBranch().equals(branchName)) {
                return new CheckoutPlan.AlreadyOnBranch(branchName);
            }
            if (!preflight.conflictingPaths().isEmpty()) {
                return new CheckoutPlan.Conflicts(preflight.conflictingPaths());
            }
            return new CheckoutPlan.Ready(preflight.branchExists(), !preflight.sameTree());
        } catch (IOException | GitAPIException | RuntimeException e) {
            String failure = describe(e);
            LOGGER.warn("git checkout (plan, branch={}) failed in {}: {}", branchName, worldDir, failure, e);
            return new CheckoutPlan.Failed(failure);
        }
    }

    /**
     * Switches to {@code branchName}, creating it at the current commit
     * first if it doesn't already exist (like {@code git checkout -b}).
     * Refuses only if a file that would actually be touched by the switch
     * is also currently uncommitted — see {@link #findConflictingPaths}
     * for why a blanket "is anything, anywhere, dirty" check isn't right
     * for a live Minecraft world.
     */
    public static CheckoutResult checkout(File worldDir, String branchName) {
        if (!isRepo(worldDir)) {
            return new CheckoutResult.Failed("Not a git repository. Run /git init first.");
        }
        if (branchName == null || branchName.isBlank()) {
            return new CheckoutResult.Failed("Branch name cannot be empty.");
        }
        try (Git git = Git.open(worldDir)) {
            CheckoutPreflight preflight = computePreflight(git, branchName);
            if (preflight.currentBranch().equals(branchName)) {
                return new CheckoutResult.AlreadyOnBranch(branchName);
            }
            if (!preflight.conflictingPaths().isEmpty()) {
                return new CheckoutResult.Conflicts(preflight.conflictingPaths());
            }
            boolean requiresRestart = !preflight.sameTree();
            git.checkout()
                .setName(branchName)
                .setCreateBranch(!preflight.branchExists())
                .call();
            return new CheckoutResult.Switched(branchName, requiresRestart);
        } catch (IOException | GitAPIException | RuntimeException e) {
            String failure = describe(e);
            LOGGER.warn("git checkout (branch={}) failed in {}: {}", branchName, worldDir, failure, e);
            return new CheckoutResult.Failed(failure);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Shared groundwork for {@link #planCheckout(File, String)} and
     * {@link #checkout(File, String)}, so preview and execution can never
     * disagree about which paths conflict or whether content would
     * actually change.
     */
    private record CheckoutPreflight(
        List<String> conflictingPaths, boolean branchExists, boolean sameTree, String currentBranch
    ) {}

    private static CheckoutPreflight computePreflight(Git git, String branchName) throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        String currentBranch = repo.getBranch();

        Ref targetRef = repo.findRef("refs/heads/" + branchName);
        boolean branchExists = targetRef != null;

        if (!branchExists) {
            // A brand-new branch is created at HEAD, so it's always tree-identical
            // to HEAD — nothing can conflict with a switch that changes no content.
            return new CheckoutPreflight(List.of(), false, true, currentBranch);
        }

        ObjectId currentTree = repo.resolve("HEAD^{tree}");
        ObjectId targetTree = repo.resolve("refs/heads/" + branchName + "^{tree}");
        boolean sameTree = currentTree != null && currentTree.equals(targetTree);
        List<String> conflicts = sameTree ? List.of() : findConflictingPaths(git, currentTree, targetTree);

        return new CheckoutPreflight(conflicts, true, sameTree, currentBranch);
    }

    /**
     * Paths that both (a) differ in content between {@code oldTree} and
     * {@code newTree}, and (b) are currently uncommitted (modified, staged,
     * missing, or untracked) in the working tree — i.e. paths checkout would
     * genuinely need to overwrite something uncommitted for. This mirrors
     * real git's own checkout safety check ("your local changes ... would be
     * overwritten"), which only blocks on files actually involved in the
     * switch — not a blanket "is anything, anywhere, dirty" check.
     *
     * <p>That distinction matters a lot here: a live Minecraft world's
     * autosave constantly rewrites {@code level.dat}, region files, player
     * data, and more, regardless of what a player actually built — a
     * blanket dirty check would treat the working tree as "uncommitted"
     * almost permanently, even moments after a deliberate
     * {@code /git add} + {@code /git commit}, and block every checkout.
     */
    private static List<String> findConflictingPaths(Git git, ObjectId oldTree, ObjectId newTree)
            throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        Set<String> changedBetweenTrees = new HashSet<>();
        try (ObjectReader reader = repo.newObjectReader()) {
            CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
            oldTreeParser.reset(reader, oldTree);
            CanonicalTreeParser newTreeParser = new CanonicalTreeParser();
            newTreeParser.reset(reader, newTree);

            List<DiffEntry> diffs = git.diff()
                .setOldTree(oldTreeParser)
                .setNewTree(newTreeParser)
                .setShowNameAndStatusOnly(true)
                .call();
            for (DiffEntry entry : diffs) {
                addIfReal(changedBetweenTrees, entry.getOldPath());
                addIfReal(changedBetweenTrees, entry.getNewPath());
            }
        }

        Status status = git.status().call();
        Set<String> dirtyPaths = new HashSet<>();
        dirtyPaths.addAll(status.getAdded());
        dirtyPaths.addAll(status.getChanged());
        dirtyPaths.addAll(status.getRemoved());
        dirtyPaths.addAll(status.getModified());
        dirtyPaths.addAll(status.getMissing());
        dirtyPaths.addAll(status.getUntracked());

        changedBetweenTrees.retainAll(dirtyPaths);
        return changedBetweenTrees.stream().sorted().toList();
    }

    private static void addIfReal(Set<String> paths, String path) {
        if (path != null && !path.equals(DiffEntry.DEV_NULL)) {
            paths.add(path);
        }
    }

    private static String shortBranchName(String fullRefName) {
        String prefix = "refs/heads/";
        return fullRefName.startsWith(prefix) ? fullRefName.substring(prefix.length()) : fullRefName;
    }

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
