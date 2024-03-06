# Nea Coder Pack

> Mapping extensions to 1.8.9 MCP

Currently, requires zwirn installed in the local maven repo:

```sh
git clone https://github.com/nea89o/zwirn
cd zwirn
./gradlew publishToMavenLocal
```

## Editing workflow

- `./gradlew unpackMappings`
- `./gradlew launchEnigma`
- `./gradlew generateDiffTiny`
- `./gradlew generateMappingPatches`

Usage in other projects is not possible right now, but a gradle plugin will eventually follow.
