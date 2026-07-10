# Changelog

All notable changes to GitMC are documented here. The format is loosely based
on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) as
soon as it has a non-`0.x` release.

## [Unreleased]

### Added

- Default `.gitignore` is written into the world save directory on
  `/git init`. Skips if the user already has one. Covers `session.lock`
  (held while the world is loaded), `level.dat_old` (regenerated every
  save), `logs/`, and `crash-reports/`. The chat success message reports
  whether the default was written.
- **`/git add <path>`** — stages files matching the given JGit file
  pattern (e.g. `.`, `*`, `region/`, `region/r.0.0.mca`). Returns the
  number of files staged, or `NothingMatched` if the pattern matched
  nothing.
- **`/git add <pos>`** and **`/git add <from> <to>`** — stage by block
  coordinate instead of file pattern (same argument shape as vanilla
  `/fill`, including `~`-relative coordinates). Resolved via a new
  `dev.polybit.gitmc.git.RegionFiles` helper:
  - Converts the coordinate/range to the region-file coordinates it
    spans (a region file is 512×512 blocks / 32×32 chunks in the X/Z
    plane; Y doesn't affect which file a position falls into, since a
    region file covers a full chunk column top to bottom).
  - For each spanned region coordinate, stages whichever of
    `region/r.X.Z.mca`, `entities/r.X.Z.mca`, `poi/r.X.Z.mca` actually
    exist on disk — files that were never generated are silently
    skipped, not an error.
  - Resolves the dimension folder (including custom/modded dimensions)
    via `LevelStorageAccess.getDimensionPath`, the same method
    Minecraft itself uses, rather than reimplementing the
    DIM-1/DIM1/`dimensions/<namespace>/<path>` convention by hand.
    Reaching it required a new `MinecraftServerAccessor` mixin
    (`@Accessor` on the protected `storageSource` field) — the same
    class of mixin as `BlockItemMixin`, just simpler (no `@Inject`
    logic, just a getter shim).
  - Dimension is the executing source's current one
    (`CommandSourceStack.getLevel().dimension()`), matching how
    vanilla `/fill` and `/setblock` resolve "current dimension".
  - `GitManager.addPaths(File, Collection<String>)` stages an
    already-resolved set of literal paths in one JGit `AddCommand`
    call, as opposed to `add(File, String)`'s single glob pattern.
- **`/git commit [message]`** — creates a commit from whatever is
  currently staged. The author and committer are set to the Minecraft
  player who ran the command, with email
  `<name>.<uuid>@gitmc.invalid` (the `.invalid` TLD is non-routable, so
  the email is safe to publish). If no message is given, defaults to
  `"Snapshot by <playername>"`. Console / command-block sources fall
  back to `Server <server@gitmc.invalid>`. A successful commit also
  clears the `/git status` block-change overlay.
- **`/git status`** — in-world overlay highlighting blocks a player has
  placed (translucent green), replaced (translucent yellow), or broken
  (translucent red, as a ghost outline) since the last commit. This
  replaces the earlier file-based staged/unstaged/untracked text output
  — see the `block` package below for the tracking design. Three modes:
  - `/git status` (no argument) — full opacity for 30 seconds
    (`BlockChangeTracker.TIMED_VISIBLE_MILLIS`), then fades out over
    3 seconds (`TIMED_FADE_MILLIS`). Re-running it restarts the window.
  - `/git status show` — full opacity until explicitly hidden.
  - `/git status hide` — hidden immediately, canceling any active timer
    or persistent state.

  The countdown and fade are a pure function of wall-clock time
  (`BlockChangeTracker.currentOpacity()`), recomputed every render
  frame — no ticking task or scheduled callback drives it.
  - `BlockChangeTracker` (new `dev.polybit.gitmc.block` package) records
    only direct player actions: placing (via `BlockItemMixin`, since
    Fabric API has no bundled block-place event) or breaking
    (`PlayerBlockBreakEvents.AFTER`, registered in `GitMC.onInitialize`).
    Physics-driven changes (pistons, liquids, crop growth, redstone,
    explosions, falling blocks) are deliberately excluded to keep the
    overlay meaningful.
  - Per position: the first touch since the last commit records the
    "original" state; later touches update only the "current" state.
    Reverting a position back to its original state drops it from
    tracking entirely (net-zero change).
  - Tracking is in-memory and resets on process restart — it's a live
    diff view, not a persisted log.
  - Rendering uses a new `client` source set (`GitMCClient`, registered
    as the `client` entrypoint), hooking
    `LevelRenderEvents.COLLECT_SUBMITS` and emitting a translucent
    filled cube per tracked position via
    `SubmitNodeCollector.submitCustomGeometry` and
    `RenderTypes.debugFilledBox()` — Minecraft 26.2's rendering pipeline
    replaced the older `WorldRenderEvents` + direct `VertexConsumer`
    pattern with this submit-node/collector architecture.
  - Scoped to singleplayer/LAN for now: the tracker is a single
    in-process instance shared by the client and the integrated server,
    so the `show`/`hide` toggle (run via a server-side command) reaches
    the renderer (client-side) without a network channel. A real
    dedicated server still runs the tracking and reports a count via
    `/git status`, but remote clients won't see the overlay until a
    networking layer is added.
- Build workflow: `./gradlew installMod` now builds and copies the jar
  into the launcher's `mods/` directory (default `%APPDATA%\.minecraft\mods`,
  override with `-Pgitmc.mods.dir=…` or `GITMC_MODS_DIR`).

### Changed

- Renamed the in-game root command from `/gitmc` to `/git`. The mod's
  identifier (`gitmc`), package (`dev.polybit.gitmc`), jar file name,
  and class names are unchanged. Error messages that referenced
  `/gitmc init` / `/gitmc add` now point at `/git init` / `/git add`.
- `/git status` no longer prints git's own staged/unstaged/untracked
  file list — see the block-change overlay entry above. `GitManager`'s
  `status()` method and `StatusResult` type were removed as dead code.

### Planned

- `/git log` with player attribution
- `/git branch` / `/git checkout`
- `/git revert`
- Dedicated-server networking for the block-change overlay
- Optional Mod Menu integration

## [0.1.0] — 2026-07-09

The initial skeleton, end-to-end.

### Added

- Fabric `ModInitializer` entry point (`dev.polybit.gitmc.GitMC`).
- `/git` command tree with a single subcommand, `init`, that bootstraps
  a JGit repository at the root of the currently loaded world's save
  directory.
- `GitManager` — a thin wrapper around JGit that returns a sealed
  `InitResult` (`Created` / `AlreadyExists` / `Failed`) so the command
  handler never has to deal with checked exceptions.
- Loom 1.17 build on Gradle 9.6.1 against the non-obfuscated Minecraft
  26.2 jar (`net.minecraft:minecraft-merged-deobf:26.2`), with
  Fabric API `0.154.2+26.2` and Fabric Loader `0.19.3`.
- JGit `6.10.0` is bundled as a nested jar under `META-INF/jars/`, so
  no separate install is required on the user's machine.
- Build configuration that pulls the right Loom plugin id
  (`net.fabricmc.fabric-loom`) for the 26.x non-obfuscated path,
  uses the post-Loom-1.14 `loom { mods { … } }` block, and works on
  Java 25.
- README, CHANGELOG, `.editorconfig`, and a permissive `.gitignore`.

### Notes

- Mod bytecode targets `--release 25` (the minimum for Minecraft 26.x).
- `org.gradle.configuration-cache` is set to `false` to match the
  official example mod and work around the open Loom/IntelliJ issue
  tracked at [FabricMC/fabric-loom#1349](https://github.com/FabricMC/fabric-loom/issues/1349).
