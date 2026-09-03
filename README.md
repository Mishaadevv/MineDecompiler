# Minecraft Source Reconstructor (`mcdecompiler`)

A lightweight, local-first **standalone decompiler tool** for Minecraft Java Edition.
It is **not a Minecraft mod** — it does not use Forge, Fabric, NeoForge or any mod loader,
it is never loaded by the game. The Minecraft `.jar` is purely an **input file**:
the tool reads its Java bytecode and reconstructs a readable, structured Java project
into a **user-chosen output folder**.

```text
minecraft.jar
      ↓
MinecraftDecompiler (this tool)
      ↓
D:\MinecraftSource\1.1\
├── src\main\java\...   decompiled sources, package structure preserved
├── resources\          non-class JAR resources
├── mappings\           mappings used + drop zone for your own
├── metadata\           provenance (decompilation.json, version.txt)
├── reports\            report.txt with errors/warnings
└── README.md           per-project notes + stats
```

## What it does

- You pick a local Minecraft `.jar` → the tool detects the version → picks a
  `VersionProfile` → decompiles → applies mappings when available → writes `.java` files.
- Works **offline-capable**: the input JAR is never uploaded anywhere.
  Mappings are found locally first; when missing, the tool can fetch them
  automatically (see *Automatic mappings* below) and caches them, so every
  version downloads at most once. `--offline` disables all network use.
- If no mappings exist (typical for Alpha/Beta), it still produces readable sources
  with synthetic names and `NOTE: no mappings were available` markers. One bad class
  never kills the run: a fallback stub is generated and processing continues.

## Supported versions

| Era | Examples | Profile | Mappings |
|---|---|---|---|
| Alpha | a1.x | `LegacyAlphaProfile` | none / community Tiny |
| Beta | b1.7.3 | `BetaProfile` | community SRG/TSRG/Tiny |
| Legacy Release | 1.0 – 1.12 | `LegacyReleaseProfile` | community SRG/TSRG |
| Modern Release | 1.13+ | `ModernReleaseProfile` | Mojang `client.txt`/`server.txt` (you provide) |
| Future / unknown | ? | `FutureProfile` | any recognized format, best-effort |

Version detection uses (in order): `--version` override → manifest →
`version.json`/embedded metadata → known classes & package signatures →
obfuscation patterns → JAR filename. Low confidence never blocks: it lists
candidates and continues best-effort.

## Requirements

- Java 17+ (to run; Gradle toolchain downloads nothing — uses your JDK)
- No Minecraft installation, no game files in this repo, no accounts.

## Install / build

```bash
gradlew build          # compile + tests -> build/libs/
gradlew fatJar         # standalone build/libs/mcdecompiler-all.jar
gradlew packageExe     # real Windows program (see below)
```

## Windows EXE

```bash
gradlew packageExe
```

produces a self-contained program (needs a JDK 17+ only to *build*;
[jpackage](https://docs.oracle.com/en/java/javase/17/jpackage/) ships with the JDK,
no extra downloads, no WiX needed):

```text
build/dist/MinecraftDecompiler/
├── MinecraftDecompiler.exe   GUI (double-click to run, own bundled Java)
└── mcdecompiler-cli.exe      CLI twin (console), same core:
                                  mcdecompiler-cli.exe input.jar --output <dir>
```

The EXE carries its own trimmed Java runtime, so it runs even on PCs with
no Java installed at all.

Plain-JAR users are covered too: the jar's `Main-Class` is a tiny
Java-8-compatible bootstrap (`app.bootstrap.Bootstrap`, class-file v52).
It loads even on an ancient default JVM, finds the best Java 17+ on the
computer and relaunches the program with it — so `java -jar` just works.

## Automatic Java selection

The program needs Java 17+. If it is ever started with an older Java
(e.g. double-clicking the plain JAR on a PC whose default Java is 8),
it does not die with `UnsupportedClassVersionError`. Instead it:

1. scans the computer (`JAVA_HOME`, `PATH`, vendor install folders) for JVMs,
2. verifies each by running `java -version`,
3. automatically relaunches itself with the lowest sufficient Java (17+),
4. and only if none exists prints exactly what was found and how to fix it.

Discovered runtimes are listed in the GUI under **Settings** and via the log.
The `javap` fallback engine likewise uses an auto-discovered JDK tool, so it
works even when the app itself runs on a bare JRE. The EXE build makes all of
this moot by bundling its own runtime — auto-selection then only matters for
external `javap` lookup.

Run the GUI (needs a display):

```bash
gradlew run
# or
java -jar build/libs/mcdecompiler-all.jar --help   # CLI (same core)
java -cp build/libs/mcdecompiler-all.jar app.ui.App
```

The GUI:

```text
Minecraft JAR:   [ C:\Minecraft\versions\1.1\minecraft.jar ] [Browse...]
Output folder:   [ D:\MinecraftSource\1.1 ] [Choose Folder...] [Suggest Folder]
Version:         [ Auto____________ ]   Decompiler: [ Auto ]   Mappings: [ Auto___ ] [Find Mappings]
[ Decompile ]   progress: [██████░░░░] 42%   Current: net.minecraft.client.Minecraft.java
```

## Automatic mappings (Find Mappings + auto-download)

You never need to hunt mapping files by hand — unless you want to.

**Where the tool looks (in order):**
1. your explicit choice (Mappings field / `--mappings`),
2. `<output>/mappings/` and `./mappings/` drop zones,
3. local auto-find: JAR siblings, your mappings library, the shared cache,
4. **auto-download** from official/community servers into
   `~/.minecraft-decompiler/mappings/` (one-time per version, then offline).

**Download sources (HTTPS, verified, logged):**
- modern versions → official Mojang ProGuard mappings
  (`piston-meta.mojang.com`, same mechanism every launcher uses;
  SHA-1 verified against Mojang's metadata),
- alpha/beta/legacy (up to 1.14.4) → OrnitheMC Feather (CC0-1.0)
  (`maven.ornithemc.net`, SHA-1 verified, Tiny format).

The GUI **Find Mappings** button does all of the above and fills the field
(single hit) or lets you pick (scored list). Picking a JAR also auto-fills
the output folder and strong mapping hits by itself. Decompile works either
way: with mappings you get real names, without them — clean synthetic names.
`--offline` / the *Download mappings automatically* checkbox turns the
network step off entirely.

`[Open Output Folder]` opens the chosen directory in the OS file manager;
`[Open Project]` shows the result in the built-in viewer
(`Ctrl+P` quick-open, `Ctrl+Shift+F` search all, `Ctrl+Click` go to definition).

## CLI usage

```bash
mcdecompiler input.jar --output <directory> [options]

  --output, -o <dir>     Output directory (required)
  --version <id>         Override detection, e.g. 1.1
  --decompiler <name>    auto | vineflower | cfr | javap   (default auto)
  --mappings <file|dir>  Mojang ProGuard, Tiny, SRG/TSRG or .properties
  --threads <n>          default: CPU count
  --no-cache             disable ~/.minecraft-decompiler cache
  --list-decompilers     list engines
```

Examples:

```bash
mcdecompiler client.jar --output ./minecraft-src
mcdecompiler client.jar --version 1.1 --output "D:\MinecraftSource\1.1"
mcdecompiler client.jar --decompiler vineflower --mappings ./mappings --output ./src
```

GUI and CLI share one core (`app.pipeline.DecompilationPipeline`) —
there is exactly one implementation of the pipeline.

## Architecture

```text
GUI (Swing) / CLI ──► Core (DecompileOptions, GameVersion, stats)
      │
      ▼
JAR Reader (streaming, read-only) ──► VersionDetector ──► VersionProfile
      │                                        (per-era behavior, no big if/else)
      ▼
BytecodeAnalyzer (ASM) ──► ClassGraph (inheritance / refs for navigation)
      │
      ▼
DecompilerRegistry: Vineflower (primary) │ CFR-compat │ javap fallback
      │
      ▼
SourceProcessor: remap → import cleanup → synthetic cleanup → format → validate
      │
      ▼
ProjectGenerator ──► output tree + metadata + report     CacheManager (sha256)
```

Why Swing and not JavaFX: zero extra runtime/Native deps, sub-second startup,
~30 MB idle RAM, native look-and-feel, fully async workers. The UI is thin glue
over the core, so a JavaFX frontend can be added later without touching the pipeline.

## Adding a new VersionProfile

```java
public final class MyEraProfile implements VersionProfile {
    public String id() { return "my-era"; }
    public String label() { return "My Era"; }
    public boolean handles(GameVersion v) { /* era check */ return false; }
}
// register: new VersionRegistry().register(new MyEraProfile());
// or via ServiceLoader: META-INF/services/app.versions.VersionProfile
```

Profiles declare: detection affinity, mappings format + hint, decompiler options,
post-processing flags, obfuscation/package notes. No core changes needed.

## Adding a MappingProvider

```java
public final class MyMappings implements MappingProvider {
    public String name() { return "mine"; }
    public MappingSet loadMappings(GameVersion v) {
        MappingSet m = new MappingSet();
        m.mapClass("a", "net/minecraft/Thing");
        m.mapMethod("a", "b", "()V", "tick");
        return m; // empty set = "no mappings", never null
    }
}
```

Supported out of the box: Mojang ProGuard (`client.txt`/`server.txt`),
Tiny v1/v2, SRG/TSRG, `.properties` (`obf.name=named.name`).
`FileMappingProvider` auto-detects the format.

## Adding a DecompilerEngine

```java
public final class MyEngine implements DecompilerEngine {
    public String name() { return "mine"; }
    public String label() { return "My Decompiler"; }
    public boolean isAvailable() { return true; }
    public Map<String, String> decompile(Path jar, GameVersion v, DecompileOptions o,
            DecompileStats s, DecompileProgressListener l, Map<String, String> extra) {
        ... // per-class failures -> stats.addError(...), never throw the whole run
    }
}
// new DecompilerRegistry().register(new MyEngine());
// or via ServiceLoader: META-INF/services/app.decompiler.DecompilerEngine
```

## Known limitations

- Output is a **best-effort reconstruction**, never claimed to be Mojang's original
  sources. Names without mappings are synthetic; generics/lambdas/switches depend
  on decompiler quality for that bytecode era.
- Very old (Alpha/Beta) jars may contain pre-Java-5 bytecode quirks; Vineflower
  handles most, the rest become annotated fallback stubs.
- Compiling the output requires adding the era's libraries (LWJGL, brigadier, …)
  yourself — the tool intentionally ships zero game dependencies.
- Network is used only for optional mappings auto-download (Mojang/OrnitheMC,
  cached, `--offline` disables it). If you prefer full manual control,
  drop Mojang's `client.txt`/`server.txt` or community packs into
  `<output>/mappings/` or pass `--mappings` — downloads are skipped when
  local files already cover the version.

## Legal

This repository contains **no Minecraft binaries, no game assets, no obfuscated
game code**. It is a clean-room tool that processes JARs you provide locally.
Decompiled output is for interoperability, research and modding education;
respect Mojang's EULA and do not redistribute game content.
