# Development practices

Keep commits focused: separate build changes, mechanical moves, and behavior fixes. Explain the problem, resulting behavior, and relevant validation in each change. Do not bundle unrelated features into a cleanup.

## Code structure

Keep Minecraft/Fabric callbacks at the edge. Parsing, item normalization, authorization, and aggregation should be testable without Minecraft. Pass time explicitly or inject a clock into stateful logic. Name timing constants and document what expires and why.

Use descriptive names, conventional Kotlin formatting, and explicit state transitions. Avoid compressed multi-statement lines in new code. Preserve upstream notices and record provenance when updating item data.

## Verification

Run `./gradlew build -Pminecraft_version=26.1` and then `./gradlew build -Pminecraft_version=26.1.2` (quote the property argument in PowerShell). Builds share an output directory, so do not run both targets concurrently in one checkout. Build includes tests and final-JAR verification. GitHub Actions checks both targets independently.

Regression tests should reproduce a failure or verify a meaningful invariant. Prefer event-sequence replays with a fake clock over sleeps. Test rejected gains and duplicate events as well as successful harvests.

Passing unit tests does not prove Minecraft integration works. Changes affecting callbacks, GUI rendering, model recognition, or packet ordering need an in-game check. Report exactly what was checked, the loaded mod/Minecraft versions, and any remaining uncertainty. Never describe a synthetic replay as a recording from the game.

Keep credentials, player saves, runtime logs, and local caches out of commits. Debug exports must omit raw chat, player IDs, server addresses, and profile names.
