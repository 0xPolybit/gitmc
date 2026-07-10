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
- **`/git status [show|hide]`** — toggles an in-world overlay highlighting
  every block a player has placed, replaced, or broken since the last
  commit: **translucent green** for a new block, **translucent yellow**
  for a replaced block, **translucent red** for a removed block (a
  ghost outline at the now-empty position). `/git status` alone and
  `/git status show` both turn it on; `/git status hide` turns it off.
  See [Block-change overlay](#block-change-overlay) below for exactly
  what counts as a tracked change and its current limitations.
- **`/git add <path>`** — stage files matching the given JGit pattern.
  `.` adds everything in the world save (respecting `.gitignore`),
  `*` is top-level only, and you can target specific files or
  directories (e.g. `region/`, `playerdata/yourname.dat`).
- **`/git commit [message]`** — commit whatever is currently staged.
  The author and committer are the Minecraft player who ran the
  command; the message defaults to *"Snapshot by &lt;playername&gt;"*
  if you don't pass one. Successfully committing also clears the
  `/git status` overlay, since everything up to that point is now
  part of the repository's history.

### Planned

- `/git log` — list recent commits with player attribution and timestamps.
- `/git branch [name]` / `/git checkout <branch>` — branch to try risky
  changes, then come back.
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

**Lifetime:** tracking is in-memory only. A server/game restart clears it
(as if everything since the last commit had already been committed) — it
does not persist to disk. This only affects what the overlay highlights;
the actual blocks in the world are never touched by any of this.

**Current scope:** singleplayer and LAN. The overlay reads tracked changes
directly from the same JVM the integrated server runs in, so there is no
networking involved yet. On a real dedicated server, `/git status`
still runs and reports a change count, but remote clients won't see the
colored overlay until a networking layer is added (see Roadmap).

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
├── status [show|hide]    Toggle the block-change overlay (default: show).
├── add <path>            Stage files matching <path> (`.`, `*`, a directory, or a specific file).
└── commit [message]      Commit whatever is staged, attributing the author to the running player.
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
│   │   │   │   └── GitManager.java               # JGit wrapper
│   │   │   └── mixin/
│   │   │       └── BlockItemMixin.java           # Detects successful player block placement
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
- [ ] Dedicated-server networking for the block-change overlay
- [ ] `/git log` with player attribution
- [ ] `/git branch` / `/git checkout`
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
