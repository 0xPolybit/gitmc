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
- Block-level change tracking: `/git init` captures the position +
  state of every non-air block in every chunk that's been loaded for the
  world (plus the chunk sections that contain them) into a baseline
  persisted to `<world>/gitmc/baseline.nbt`. `/git status` then walks the
  loaded chunks and categorizes each block into Untracked (green,
  newly placed), Modified (yellow, type or state property changed), or
  Removed (red, now air). The chat output reports the per-category
  counts; the in-world translucent overlay (Phase B) renders the actual
  boxes.
- `/git status` accepts `show` (persistent highlights until
  `/git status hide`) or `hide` (clear active highlights). With no
  argument, `/git status` shows with a 30 s auto-fade.
- Removed file-level JGit plumbing (the previous `/git add` /
  `/git commit` commands) and the JGit dependency from the build.

### Changed

- Renamed the in-game root command from `/gitmc` to `/git`. The mod's
  identifier (`gitmc`), package (`dev.polybit.gitmc`), jar file name,
  and class names are unchanged. Error messages that referenced
  `/gitmc init` / `/gitmc add` now point at `/git init` / `/git add`.
- Build workflow: `./gradlew installMod` now builds and copies the jar
  into the launcher's `mods/` directory (default `%APPDATA%\.minecraft\mods`,
  override with `-Pgitmc.mods.dir=…` or `GITMC_MODS_DIR`).

### Planned

- Client-side translucent overlay (the in-world visual that pairs
  with `/git status show`).
- `/git log` with player attribution
- `/git branch` / `/git checkout`
- `/git revert`
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
