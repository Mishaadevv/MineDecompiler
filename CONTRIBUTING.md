# Contributing

## Ground rules

- This is a **standalone decompiler tool**. Never add Forge, Fabric, NeoForge,
  mod-loader code, `mods.toml`, or anything that runs *inside* Minecraft.
  The Minecraft JAR is input data only.
- Never commit Minecraft binaries, game assets, or decompiled game sources.
- Core (`app.pipeline`, `app.core`, `app.bytecode`, …) must stay UI-independent:
  GUI and CLI both go through `DecompilationPipeline`.
- No new hard network dependencies. The tool is offline-first.

## Workflow

1. Pick or file an issue, keep changes focused.
2. Add/extend a `VersionProfile`, `MappingProvider` or `DecompilerEngine`
   instead of `if version == ...` branches (see README).
3. Add tests: fixtures live in `src/test/java/app/TestJars.java`
   (synthetic compiled classes only — no game code).
4. Verify: `gradlew build` (compiles + runs all tests).
5. Per-class failures must be recorded in `DecompileStats`, never abort a run.

## Code style

- Java 17, UTF-8, 4 spaces, no wildcard imports in new code.
- Small focused classes matching the existing package layout
  (`core`, `bytecode`, `decompiler`, `mappings`, `versions`,
  `reconstruction`, `project`, `cache`, `pipeline`, `search`, `cli`, `ui`).
