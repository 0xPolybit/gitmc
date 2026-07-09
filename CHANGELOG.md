# Changelog

All notable changes to GitMC are documented here. The format is loosely based
on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) as
soon as it has a non-`0.x` release.

## [Unreleased]

### Planned

- `/gitmc status`, `/gitmc add`, `/gitmc commit`
- `/gitmc log` with player attribution
- `/gitmc branch` / `gitmc checkout`
- `/gitmc revert`
- Built-in `.gitignore` for noisy save files
- Optional Mod Menu integration

## [0.1.0] — 2026-07-09

The initial skeleton, end-to-end.

### Added

- Fabric `ModInitializer` entry point (`dev.polybit.gitmc.GitMC`).
- `/gitmc` command tree with a single subcommand, `init`, that bootstraps
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
