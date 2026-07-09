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

- **`/gitmc init`** — initialize a git repository at the root of the currently
  loaded world's save directory. The `.git` folder is created alongside
  `level.dat`, so every part of the save (region files, player data,
  datapacks, the lot) becomes committable.

### Planned

- `gitmc status` — show modified and untracked files inside the world.
- `gitmc add <path>` / `gitmc add .` — stage changes.
- `gitmc commit [-m <msg>]` — snapshot the world state with a message.
- `gitmc log` — list recent commits with player attribution and timestamps.
- `gitmc branch [name]` / `gitmc checkout <branch>` — branch to try risky
  changes, then come back.
- `gitmc revert <commit>` — roll a world back to an earlier snapshot.
- A sensible built-in `.gitignore` for volatile files (e.g. `session.lock`,
  hot-loaded chunk regions).
- A chat-based diff viewer.

If any of those are particularly useful to you, please open an issue —
priority is roughly driven by what people actually want.

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
5. Load or create a world, then run `/gitmc init` once to bootstrap a repo
   in that world.

### Server operators

Identical to the player flow, but the jar goes into the server's `mods/`
folder. Run `/gitmc init` once as a server operator (or in the server
console) to bootstrap the repo in the world save directory.

## Usage

The mod exposes a single root command, `/gitmc`:

```
/gitmc
└── init    Initialize a git repository in the current world's save directory.
```

By default the repo is untracked after `init`. From there, drop into a shell
and use the regular `git` CLI to commit, branch, and push — or wait for
`/gitmc commit`, `/gitmc log`, and the rest of the planned commands to land
in-game.

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
└── src/
    └── main/
        ├── java/dev/polybit/gitmc/
        │   ├── GitMC.java                         # Fabric entrypoint
        │   ├── command/
        │   │   └── GitMCCommands.java             # /gitmc command tree
        │   └── git/
        │       └── GitManager.java                # JGit wrapper
        └── resources/
            └── fabric.mod.json                   # Mod metadata
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
- [x] `/gitmc init` (real JGit-backed repo in the world save)
- [x] Migration to the non-obfuscated Minecraft 26.x API and Loom plugin
- [ ] `/gitmc status`, `/gitmc add`, `/gitmc commit`
- [ ] `/gitmc log` with player attribution
- [ ] `/gitmc branch` / `gitmc checkout`
- [ ] `/gitmc revert`
- [ ] Built-in `.gitignore` for noisy save files
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
