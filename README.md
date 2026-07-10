<div align="center">

# GitMC

### Git version control for Minecraft worlds.

Snapshot, branch, and revert your world save without ever leaving the game.

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-5a8b3a?style=flat&logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric_Loader-0.19.3-dba60a?style=flat&logo=fabric&logoColor=white)](https://fabricmc.net/)
[![Fabric API](https://img.shields.io/badge/Fabric_API-0.154.2%2B26.2-7e5bbe?style=flat&logo=fabric&logoColor=white)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25%2B-e57001?style=flat&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-c4a000?style=flat)](LICENSE)

[Features](#features) • [Installation](#installation) • [Usage](#usage) • [Build from source](#build-from-source) • [Contributing](#contributing)

</div>

---

GitMC brings the familiar git workflow to Minecraft. It initializes a real git repository inside your world's save directory using [JGit](https://www.eclipse.org/jgit/), so the resulting repo is fully interoperable with stock `git` on the command line — `git log`, `git diff`, branches, merges, pushes, and pulls all work the way you'd expect.

## Table of contents

- [Features](#features)
- [Block-change overlay](#block-change-overlay)
- [Coordinate-based staging](#coordinate-based-staging)
- [Branching and checkout](#branching-and-checkout)
- [Requirements](#requirements)
- [Installation](#installation)
- [Usage](#usage)
- [Build from source](#build-from-source)
- [Project layout](#project-layout)
- [Tech stack](#tech-stack)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

## Features

### Available now

- **`/git init`** — initialize a git repository at the root of the currently
  loaded world's save directory. The `.git` folder is created alongside
  `level.dat`, so every part of the save (region files, player data,
  datapacks, the lot) becomes committable. A default `.gitignore` is
  written covering `session.lock`, `level.dat_old`, `logs/`, and
  `crash-reports/` (skipped if you already have one).
- **`/git status`** — shows an in-world overlay highlighting every block a
  player has placed, replaced, or broken since the last commit:
  **translucent green** for a new block, **translucent yellow** for a
  replaced block, **translucent red** for a removed block (a ghost
  outline at the now-empty position). Three ways to control it:
  - `/git status` (no argument) — shown for 30 seconds, then fades out
    on its own over ~3 seconds.
  - `/git status show` — shown until you explicitly hide it.
  - `/git status hide` — hides immediately, whether it was showing
    persistently or mid-countdown.

  See [Block-change overlay](#block-change-overlay) below for exactly
  what counts as a tracked change and its current limitations.
- **`/git add <path>`** — stage files matching the given JGit pattern.
  `.` adds everything in the world save (respecting `.gitignore`),
  `*` is top-level only, and you can target specific files or
  directories (e.g. `region/`, `playerdata/yourname.dat`).
- **`/git add <pos>`** / **`/git add <from> <to>`** — stage by block
  coordinate instead of file pattern: a single position, or a range
  (same shape as vanilla `/fill`, `~`-relative coordinates included).
  Resolves to whichever region/entities/poi files on disk actually
  cover that area, in your current dimension — see
  [Coordinate-based staging](#coordinate-based-staging) below.
- **`/git commit [message]`** — commit whatever is currently staged.
  The author and committer are the Minecraft player who ran the
  command; the message defaults to *"Snapshot by &lt;playername&gt;"*
  if you don't pass one. Successfully committing also clears the
  `/git status` overlay, since everything up to that point is now
  part of the repository's history.
- **`/git log [count]`** — the most recent commits (10 by default, up
  to 50 with an explicit count), newest first: short hash, message,
  author, and a humanized relative time (*"3 minutes ago"*, *"2 days
  ago"*, …). Says *"No commits yet."* if you haven't committed
  anything.
- **`/git branch`** — list local branches, current one marked. **`/git
  branch <name>`** — create a new branch at your current commit
  without switching to it.
- **`/git checkout <branch>`** — switch branches, creating `<branch>`
  first if it doesn't exist (like `git checkout -b`). Two-step for
  safety: the bare command previews what would happen (and whether it
  requires closing the world) without changing anything; **`/git
  checkout <branch> confirm`** actually performs it. See
  [Branching and checkout](#branching-and-checkout) below for why this
  needs more care in a live Minecraft world than in a normal repo.

### Planned

- `/git revert <commit>` — roll a world back to an earlier snapshot.
- A chat-based diff viewer.
- Dedicated-server networking so the `/git status` overlay is visible to
  remote clients, not just in singleplayer/LAN.

If any of those are particularly useful to you, please open an issue —
priority is roughly driven by what people actually want.

## Block-change overlay

`/git status`'s overlay is a live "diff since last commit" view of the
world, not a file-level diff — it answers "what did players actually
build or destroy", not "what changed in the underlying region files".

**What's tracked:** only direct player actions — placing a block
(right-click) or breaking one (left-click break). Physics-driven changes
(pistons, water/lava flow, crop growth, leaf decay, redstone contraptions,
explosions, falling blocks) are **not** tracked, on purpose — including
them would flood the overlay with changes nobody directly caused.

**How positions are classified:** the first time a position changes since
the last commit, its state at that moment becomes the tracked "original".
Every later change to the same position only updates the tracked
"current" state — the original stays put. If a position is changed back
to its original state (placed, then broken back to what was there),
tracking for it is dropped entirely: no net change, nothing to show.

**Lifetime of the tracked data:** in-memory only. A server/game restart
clears it (as if everything since the last commit had already been
committed) — it does not persist to disk. This only affects what the
overlay highlights; the actual blocks in the world are never touched by
any of this.

**Lifetime of the overlay's visibility** (separate from the tracked
data above) is one of three modes, controlled by which `/git status`
variant you last ran — see [Features](#features) above. The 30-second
countdown and fade are computed from wall-clock time each frame, so
running the bare `/git status` command again always restarts the
30-second window from full opacity.

**Current scope:** singleplayer and LAN. The overlay reads tracked changes
directly from the same JVM the integrated server runs in, so there is no
networking involved yet. On a real dedicated server, `/git status`
still runs and reports a change count, but remote clients won't see the
colored overlay until a networking layer is added (see Roadmap).

## Coordinate-based staging

`/git add <pos>` and `/git add <from> <to>` let you stage by location
instead of by file pattern — handy when you know roughly where you built
something but not which region file that maps to.

```
/git add 100 64 -200                  # single position
/git add 100 64 -200 250 90 -50       # a range (like /fill's <from> <to>)
/git add ~ ~ ~ ~16 ~ ~16              # relative to where you're standing
```

**How it resolves:** Minecraft partitions each dimension into 512×512-block
(32×32-chunk) region files. A coordinate or range is converted to the set
of region coordinates it spans, then for each one, whichever of
`region/r.X.Z.mca`, `entities/r.X.Z.mca`, and `poi/r.X.Z.mca` actually
exist on disk gets staged. Files that were never generated (an unexplored
area) are silently skipped, not an error.

**Y is accepted but doesn't narrow the file selection** — a region file
covers a full chunk column, top to bottom, so only the X/Z bounds affect
which files match. Giving Y is still natural since that's how you'd
normally describe a location (e.g. pasting F3 coordinates), and range mode
takes it for a familiar `/fill`-shaped command even though it isn't load-bearing.

**Dimension:** resolved from your current dimension when you run the
command (the same way `/fill` and `/setblock` do) — running it while
standing in the Nether stages Nether region files, not Overworld ones.

## Branching and checkout

A plain `git checkout` just rewrites files nothing else has open. A
running Minecraft world isn't like that — the server keeps chunks,
player data, and other state in memory and periodically autosaves it
back to disk. If a checkout swapped world files out from under a still
*running* world, the server's stale in-memory state could autosave right
back over whatever was just checked out, silently undoing it. GitMC's
checkout is built around that risk:

- **`/git checkout <branch>`** (no `confirm`) never touches anything. It
  previews what would happen: whether `<branch>` will be created,
  and — critically — whether the checkout would actually change any
  file content. Switching to a brand-new branch never does (it starts
  identical to where you are); switching to a branch that's diverged
  usually does.
- **`/git checkout <branch> confirm`** forces a full save first (so the
  check above reflects real on-disk state, not stale in-memory data),
  refuses outright if you have uncommitted changes, and — only if the
  checkout actually changes file content — closes the world afterward
  so the stale in-memory state can never overwrite the fresh checkout.
  Just reopen the world to continue on the new branch.
- The common "branch to try something risky" flow (`/git checkout
  new-branch-name confirm`, right after creating it) does **not** close
  the world, since nothing has diverged yet — you keep playing
  immediately. It's switching *back* to a branch with different history
  that triggers the safe-close.
- Preview and confirm each independently recompute everything from
  scratch — there's no cached state between the two calls that could go
  stale or be tricked into skipping a check.

This is currently a singleplayer/LAN feature for the same reason as the
`/git status` overlay: on a dedicated server, closing the world means
stopping the server for every connected player, which isn't something
GitMC does automatically without everyone's awareness. Multiplayer-aware
checkout (e.g. requiring all players to be out of the world first) is a
possible future improvement.

## Requirements

| | Minimum | Notes |
| --- | --- | --- |
| Minecraft | `26.2` | Any `26.x` should work; the build pins `~26.2`. |
| Fabric Loader | `0.19.3` | |
| Fabric API | `0.154.2+26.2` | Match this to your Minecraft version. |
| Java | `25` | Required to *build*. The mod itself runs inside Minecraft's bundled JRE. |

## Installation

### Players

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2.
2. Grab the [latest release](https://github.com/polybit/gitmc/releases) jar
   (or [build from source](#build-from-source)).
3. Drop the jar into your `.minecraft/mods/` folder.
4. Launch Minecraft 26.2.
5. Load or create a world, then run `/git init` once to bootstrap a repo
   in that world.

### Server operators

Identical to the player flow, but the jar goes into the server's `mods/`
folder. Run `/git init` once as a server operator (or in the server
console) to bootstrap the repo in the world save directory.

## Usage

```
/git
├── init                  Initialize a git repository in the current world's save directory.
├── status                Show the block-change overlay for 30s, then auto-fade.
├── status show           Show the block-change overlay until explicitly hidden.
├── status hide           Hide the overlay immediately.
├── add <path>            Stage files matching <path> (`.`, `*`, a directory, or a specific file).
├── add <pos>             Stage the region file(s) covering a single block position.
├── add <from> <to>       Stage the region file(s) covering a coordinate range.
├── commit [message]      Commit whatever is staged, attributing the author to the running player.
├── log [count]           Show the most recent commits (default 10, max 50).
├── branch                List local branches, current one marked.
├── branch <name>         Create a branch at the current commit without switching to it.
├── checkout <branch>     Preview switching to <branch> (creating it first if new). Doesn't change anything.
└── checkout <branch> confirm   Actually switch, saving first and closing the world if content changed.
```

By default the repo is untracked after `init`. The typical loop is
`status` (to see what you've built or destroyed) → `add .` →
`commit -m "Placed a creeper farm at spawn"`, but you can also work in
smaller slices (`add region/`, then `commit -m "..."`, then
`add playerdata/`, then `commit -m "..."`). The author is always the
Minecraft player who ran the command — run it as someone else and the
commit is attributed to them.

## Build from source

Requires **Java 25** (or newer) on `PATH` or `JAVA_HOME`. Gradle is vendored
via the wrapper, so you don't need to install it.

```sh
git clone https://github.com/polybit/gitmc.git
cd gitmc
./gradlew build            # or .\gradlew.bat on Windows
```

The built jar lands at `build/libs/gitmc-<version>.jar`.

The first build downloads Gradle 9.6.1, Fabric Loom 1.17, and the Minecraft
26.2 jar into `~/.gradle`, so it can take a few minutes. Subsequent builds
are much faster.

### Verifying the build

```sh
./gradlew check
```

Runs Loom's static checks (access wideners, included jars, mod metadata).

### Installing into your live `mods/` folder

`./gradlew installMod` builds the mod and copies the jar straight into the
launcher's `mods/` directory so the next Minecraft launch picks it up —
handy when you're iterating on the mod with the game open on another monitor.

The destination defaults to `%APPDATA%\.minecraft\mods` on Windows. Override
it per-invocation with a Gradle property or environment variable:

```sh
# One-off, this terminal only
./gradlew installMod -Pgitmc.mods.dir=D:/Games/MultiMC/instances/26.2/mods

# Or set once for the shell
export GITMC_MODS_DIR=/path/to/mods     # macOS / Linux
setx GITMC_MODS_DIR D:\Games\...\mods   # Windows
./gradlew installMod
```

### Targeting a different Minecraft version

The Minecraft, Yarn (none, since 26.x is non-obfuscated), Fabric API, and
Fabric Loader versions are all pinned in `gradle.properties`. To build for
a different release, bump those four values together and rerun
`./gradlew build`. The non-obfuscated plugin id
(`net.fabricmc.fabric-loom`) is required for any `26.x` target.

## Project layout

```
gitmc/
├── build.gradle              # Fabric Loom build script
├── settings.gradle           # Plugin repository setup
├── gradle.properties         # Minecraft, Fabric, and mod version pins
├── gradlew, gradlew.bat      # Gradle wrapper scripts
├── gradle/wrapper/           # Wrapper jar and metadata
├── .gitignore
├── .editorconfig
├── CHANGELOG.md
├── LICENSE
├── README.md                 # You are here
├── src/
│   ├── main/
│   │   ├── java/dev/polybit/gitmc/
│   │   │   ├── GitMC.java                        # Fabric entrypoint (common)
│   │   │   ├── block/
│   │   │   │   ├── BlockChangeTracker.java       # Player-action block-change tracker
│   │   │   │   └── BlockDelta.java               # A tracked position's before/after state
│   │   │   ├── command/
│   │   │   │   └── GitMCCommands.java            # /git command tree
│   │   │   ├── git/
│   │   │   │   ├── GitManager.java               # JGit wrapper
│   │   │   │   └── RegionFiles.java              # Coordinate → region-file resolution
│   │   │   └── mixin/
│   │   │       ├── BlockItemMixin.java           # Detects successful player block placement
│   │   │       └── MinecraftServerAccessor.java  # Exposes storageSource for dimension paths
│   │   └── resources/
│   │       ├── fabric.mod.json                   # Mod metadata
│   │       └── gitmc.mixins.json                 # Mixin config
│   └── client/
│       └── java/dev/polybit/gitmc/client/
│           └── GitMCClient.java                  # Renders the /git status overlay
└── (build files, see above)
```

## Tech stack

| Component | Version |
| --- | --- |
| Minecraft | 26.2 (non-obfuscated) |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.154.2+26.2 |
| Fabric Loom | 1.17 (SNAPSHOT) |
| Gradle | 9.6.1 |
| Java | 25 |
| JGit | 6.10.0 |

## Roadmap

- [x] Project skeleton with `ModInitializer` and Fabric command API
- [x] `/git init` (real JGit-backed repo in the world save) with a default `.gitignore`
- [x] Migration to the non-obfuscated Minecraft 26.x API and Loom plugin
- [x] `/git add`, `/git commit` with player attribution
- [x] `/git status show|hide` — in-world block-change overlay (singleplayer/LAN)
- [x] Coordinate-based `/git add` (single position or range → region files)
- [x] `/git log` with player attribution and relative timestamps
- [x] `/git branch` / `/git checkout` (preview + confirm, safe around a running world)
- [ ] Dedicated-server networking for the block-change overlay
- [ ] Multiplayer-aware checkout (currently singleplayer/LAN only)
- [ ] `/git revert`
- [ ] Optional Mod Menu integration

## Contributing

1. Fork the repo.
2. Create a feature branch: `git checkout -b feature/my-thing`.
3. Make your change. Run `./gradlew build` locally — it must succeed before
   you open a PR.
4. Open a PR against `main` with a short description of the change and the
   command(s) you added or modified.

If you're working on a sizeable feature, please open an issue first so we
can agree on the shape of the API before you sink time into it.

## License

[MIT](LICENSE) © 2026 Swastik Biswas.
