# Mod Build Workflow

Use this workspace layout:

```text
F:\tool_test\
  tool\       # Mindcraft bridge / bot
  baritone\   # custom Baritone
```

Default build and copy command:

```bat
npm run build:mods
```

This builds both mods and copies the newest jars into:

```text
%USERPROFILE%\.lunarclient\profiles\lunar\1.21\mods\fabric-1.21.11
```

If old bridge or Baritone jars need to be removed first, close Minecraft/Lunar and run:

```bat
npm run build:mods:clean
```

Direct script entry point:

```bat
.\scripts\build_mods.bat
```

Override the destination folder when needed:

```bat
.\scripts\build_mods.bat -ModsDir "%USERPROFILE%\.lunarclient\profiles\lunar\1.21\mods\fabric-1.21.11"
```
