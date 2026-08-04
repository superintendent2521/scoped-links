# Sable Scoped Links

Sable Scoped Links is a NeoForge addon that scopes Create redstone link communication by Sable sub-level.

By default, matching Create redstone links only communicate when both links are in the same scope:

- ordinary world to ordinary world
- one Sable sub-level to that same Sable sub-level

The addon patches Create's `RedstoneLinkNetworkHandler.withinRange` with a mixin, leaving Create's frequency cache and normal range logic intact.

## Config

Server config key:

```toml
[redstone_links]
redstoneLinkSubLevelScope = "SAME_SUBLEVEL_ONLY"
failOpenWhenSableApiMissing = true
```

Modes:

- `VANILLA_CREATE`: disables the filter.
- `SAME_SUBLEVEL_ONLY`: links must be in the same ordinary-world/sub-level scope.
- `SUBLEVEL_AND_WORLD`: sub-level links can talk to ordinary-world links, but different sub-levels remain isolated.

The bridge binds Sable through its companion service API when available, then falls back to known companion class names.

## Build Notes

This project targets Minecraft `1.21.1`, NeoForge `21.1.x`, and Create `6.0.x`. Building requires JDK 21.

The Gradle project pulls the NeoForge/Minecraft dev environment and the required runtime mods from Modrinth Maven. The pinned runtime artifacts are configured in `gradle.properties`:

- Create: `6.0.10+mc1.21.1`
- Sable NeoForge: `NGuyFOeE` (`2.0.0+mc1.21.1`)
- Create Aeronautics NeoForge bundled: `w7zlLnea` (`1.3.0+mc1.21.1`)

For extra local jars, put them in either `libs/` or `run/mods/`. Gradle includes every `libs/*.jar` on the compile classpath and includes both folders on the local runtime classpath.

Required runtime mods:

- `create`
- `sable`
- `aeronautics`

Those jars may have their own embedded dependencies. If NeoForge reports additional missing optional compatibility mods, add them only if you need that compatibility path.

Import the folder as a Gradle project in IntelliJ with a JDK 21 Gradle JVM, or build from the terminal with the included Gradle wrapper.
