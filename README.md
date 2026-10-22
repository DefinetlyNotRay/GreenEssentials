# GreenEssentials

Standalone client Fabric mod for Hypixel SkyBlock Greenhouse layouts and harvest profit tracking, maintained by DefinetlyNotRay. This repository builds independently of SkyHanni and NEU.

## Install

Minecraft **26.1, 26.1.2, 26.2, or 26.3**, Java **25**, Fabric Loader **0.19.0+**, Fabric API for your Minecraft version, and Fabric Language Kotlin **1.13.13+kotlin.2.4.10 or newer** are required. Use the GreenEssentials JAR matching your Minecraft version. Put the JAR and those dependencies in your instance's `mods` folder.

Build from this directory with `./gradlew build` (Windows: `.\gradlew.bat build`). The installable JAR is `build/libs/greenessentials-0.1.24-mc26.1.2.jar`; the `-sources.jar` is source code, not the mod to install. Run tests with `./gradlew test`.

For Minecraft **26.1**, build with `./gradlew build -Pminecraft_version=26.1` (PowerShell: `.\gradlew.bat build '-Pminecraft_version=26.1'`). This produces `build/libs/greenessentials-0.1.24-mc26.1.jar` with metadata requiring 26.1. The default build targets 26.1.2. Build 26.2 or 26.3 with the matching `-Pminecraft_version=26.2` or `-Pminecraft_version=26.3` property. Each JAR requires its exact Minecraft target; use the matching Fabric API. Minecraft 26.3 uses its SDL file picker and link opening, with a separate render hook and access widener.

## Use

JellyShort is included: remove the separate JellyShort JAR before installing GreenEssentials. `/ge` > **JellyShort** controls shortening/hiding and visible height (1–8). Settings persist; manual marks clear when the world changes. `/jellyshort` remains available, alongside `/ge jellyshort` and the other full command roots, and `/gejellyshort`. Vanilla and optional Sodium hooks hide the upper heads and sugar cane models without changing hitboxes. Detection is always automatic and requires all ten model tiers; it does not establish server growth stage. Use `/jellyshort mark`, `unmark`, `clear`, or `inspect` for manual control. Manual marks affect other display entities in the marked column too.

Crop counts in the HUD and `/ge items` automatically use enchanted and compacted forms, with exact leftovers. Toggle **Profit Tracker > Compact Crops** in `/ge` to show raw counts. This changes display only; stored totals and profit stay unchanged.

Open `/ge`, `/greenessentials`, or `/greenhouse` for the actual MoulConfig settings editor used by SkyHanni, including built-in search, category navigation, toggles, and a HUD scale slider. MoulConfig 4.7.2 (26.1–26.2) or 4.8.0 (26.3) is bundled and relocated; no separate UI library download is required. **GUI > Edit GUI Locations** opens a live preview: drag the tracker and scroll to resize (50% to 200%). Done/Escape saves and returns to settings. Reset position & size restores placement and scale only. `/greenhouse hud` opens the editor directly.

Open **Schematic layout library**, `/gelayouts`, `/greenhouse layouts`, or `/shgreenhouseblueprint` for the restored SkyHanni layout library: scrolling cards with mini grid previews, setup/buff/unique counts, active indicators, and per-card controls. If your profile isn't visible in the tab list, use `/greenhouse profile <name>`. Profiles are separated by server address, player UUID, and profile name. An undetected profile pauses tracking. World changes clear the detected profile and inventory baseline; automatic detection resumes when the tab list supplies the name, or you can select it again manually.

**Open the Garden's Configure Plots menu once.** The mod reads the same plot slots and `Greenhouse Plot` lore as SkyHanni and saves each plot's greenhouse flag per profile. The 5×5 plot map and coordinate boundaries also come from SkyHanni. Greenhouse detection then works when your scoreboard shows the plot's normal/custom name instead of “Greenhouse.” The Garden Area widget or Garden scoreboard location prevents saved flags from activating on other islands. `/greenhouse detect` reports the current detection status.

While standing on a Greenhouse plot, click **Capture New**, or copy a SkyShards, SkyMutations, or SkyLayouts share link/code and click **Import Link**. Portable SkyHanni `.shgreenhouse.json` files can also be copied as JSON to the clipboard, or selected with **Import File**. Each card has **Load/Unload**, **Overwrite** (recapture into that layout), **Export**, **Rotate**, and **Delete** (which requires a second click). Click a layout's name to rename it, then Save/Enter or Cancel/Escape. New captures and imports receive unused names automatically. Scrolling is smooth and cards outside the viewport cannot intercept clicks.

Missing crops and mutations receive in-world footprint boxes, labels, and semi-transparent mutation head markers. Missing or incorrect spawning surfaces receive textured translucent block previews under the full mutation footprint, including output target cells. Explicit surface cells in SkyLayouts links and captured soil are preserved through export and rotation; plant-only links use wiki growth-surface requirements. Correct soil disappears from the overlay after two stable scans. Loaded chunks are required, and a missing placement must survive two scans before being displayed. The spawned target mutation is ignored; `/greenhouse target <mutation>`, `none`, or `auto` changes this behavior for the active layout.

The Export button opens a save-file picker starting in `config/greenhouse/layouts`; `/greenhouse export <name>` writes directly into that folder. Put existing portable layout files in that directory and use `/greenhouse importfile <filename>` to import them. Saved data and settings live in `config/greenhouse/data.json`; writes use a temporary file and atomic replacement where supported. An unreadable file is preserved and saving is disabled until it is repaired.

Useful commands:

- `/greenhouse help` — command list.
- `/greenhouse capture <name>` and `/greenhouse import <name>` — capture or import the clipboard.
- `/greenhouse use <name>`, `rotate <name>`, `export <name>`, `delete <name>`.
- `/greenhouse unload` — stop displaying the current plot's schematic.
- `/greenhouse toggle` and `/greenhouse overlay` — toggle tracker or schematic.
- `/greenhouse detect` — Garden, plot, and greenhouse detection status.
- `/greenhouse stats` — total estimated harvest value for the selected Session or All-time view.
- `/greenhouse items` — complete item breakdown, including rows that do not fit on screen.
- `/greenhouse hud <x> <y>` — position the HUD.
- `/greenhouse price <INTERNAL_ID> <coins>` — override an item's price, including otherwise unpriced items.
- `/greenhouse prices` — refresh prices now.
- `/greenhouse session` — show this game run's totals for the current profile (or since its last session reset).
- `/greenhouse alltime` — show this profile's saved totals across game runs.
- `/greenhouse reset` — explain which selected view will be reset; it does not erase anything.
- `/greenhouse reset confirm` — reset the currently selected view only, retaining layouts.
- `/greenhouse reset session confirm` or `/greenhouse reset alltime confirm` — explicitly reset one view regardless of which is displayed.

## Tracker behavior and limits

The HUD shows the GreenEssentials Tracker title, item icons where available, quantities, names, individual values, total value, selected display mode, and farming time. Rows are sorted by value. `/greenhouse items` prints the full breakdown if the screen cannot fit every row.

Generic inventory and sack gains require a recent matching harvest signal. Ordinary crops use the client block-break event, not an attack/swing callback. Clicked mutation and crop-head models must change or disappear within two seconds before they authorize gains. A signal opens a two-second item-specific window, with supported rare crop drops and mushroom pet byproducts included. Unrelated crop families stay excluded. Leaving the world/profile, leaving the Greenhouse, disabling tracking, or opening a screen clears authorization. Existing inventory totals continue to update as a baseline while unauthorized, so old items do not become income when farming starts. Farming time advances only while a harvest window is active.

Inventory totals are compared in base crop units so moving stacks or compacting supported crops does not count them again. Sack hover messages supply sack-only drops. Overlapping inventory/sack signals are deduplicated, including signals arriving on opposite sides of a flush. Container screens pause inventory tracking and refresh its baseline. Pest reward chat excludes the stated reward and briefly pauses harvest attribution. Timestalk Clone and Zombuddy deaths and supported shard chat are combined so the two signals don't count one kill twice.

The HUD values base crop quantities using the better of Bazaar instant-sell and NPC value, including equivalents from supported compacted forms. This is an estimated **gross harvest value**, before Bazaar fees and expenses; it does not copy SkyHanni's full tracker sale controls. Missing prices are reported as unpriced. Public [Hypixel API](https://api.hypixel.net/) Bazaar and item-resource data are fetched asynchronously at most once every five minutes automatically. No API key is needed. Harvest counting and bundled schematic skins work without a successful price fetch.

This first standalone version needs live Greenhouse verification. Particular limits to check:

- Sack attribution requires the detailed item names in Hypixel's sack-message hover text; a summarized “other items” entry cannot be reconstructed.
- Matching items transferred or picked up during the two-second window after a harvest are not distinguishable from its drops solely by client inventory/sack signals. A recent harvest is required, but this is not server-provided proof of each individual drop.
- Deduplication and pest attribution use timing windows and can conservatively undercount simultaneous independent sources. Farming during the three seconds after a pest reward is excluded.
- Only the explicitly listed compacted crop conversions in `Catalog` are normalized. New crop forms need their recipe ratios added there.
- Mutation kills are detected from observed entity deaths and nearby names; deaths outside client visibility can be missed. Supported shard chat provides another signal.
- SkyHanni profit history is not automatically migrated. Existing layouts use the compatible portable file format.
- Disable the corresponding SkyHanni overlays when testing both mods together to avoid duplicate displays.

## Attribution

Layout codecs, portable-file validation, grid/crop/mutation scanning, target inference, and related tests are adapted from SkyHanni. This project retains the parent project's LGPL-2.1 license (see `LICENSE`). `mutation-skins.json` contains the 40 mutation skin descriptors from Hypixel's public item-resource endpoint, retrieved on 2026-10-20; texture images are resolved by Minecraft.

## 0.1.1 startup fix

Mutation skin metadata is parsed at startup, but marker item stacks are created only with a loaded world, after Minecraft binds its item components. This fixes the `Components not bound yet` crash in 0.1.0. Cached stacks are discarded when the world changes.

## 0.1.2 plot detection and original UI

Ports SkyHanni's Configure Plots flags, profile storage, coordinate map, and boundary behavior. Restores its original scrolling card-based layout library, previews, rename editor, file pickers, overwrite/unload actions, role colors, and delete confirmation. Existing standalone layouts and totals are preserved; old save files receive an empty plot map until Configure Plots is opened.

## 0.1.3 capture overlap fix

Captured layouts can contain both a mutation marker and an independent crop in the same planting cell. The portable-file validator now accepts those two layers and permits up to 200 entries across the 10×10 grid. Duplicate crops, duplicate mutations, and overlapping physical mutation footprints remain invalid. This fixes capture/overwrite failing with “The layout contains duplicate grid entries.”

## 0.1.4 tracker item list and harvest authorization

Restores the item breakdown with quantities and per-item values. Generic inventory/sack gains now require a recent harvest of the corresponding crop; standing on a greenhouse plot alone is insufficient. Clicks without a crop break or a change to the clicked plant model do not authorize gains. Existing totals are retained because historical harvests cannot be separated from transfers retroactively. Use `/greenhouse reset confirm` if you want a clean history under the stricter rule.

## 0.1.5 Session / All-time controls

`/greenhouse session` and `/greenhouse alltime` switch the HUD, `/greenhouse items`, and `/greenhouse stats` together. Both totals continue receiving harvests regardless of the displayed mode. Session history is kept in memory per server/account/profile for this game run; switching worlds or profiles does not clear it, and restarting the game does. All-time history remains saved in the existing profile data. The selected display mode is saved. Reset only affects the explicitly chosen or currently displayed view, and never deletes layouts. Per-hour estimates have been removed from both the HUD and command output.

## 0.1.9 clickable HUD controls

The tracker HUD now includes **Session**, **All-time**, and **Reset** buttons. Open chat (T) or your inventory (E) to free the cursor and click them directly. The selected view is highlighted. Reset affects only that view for the current profile and requires clicking **Confirm reset** within five seconds. Changing view/profile or closing the screen cancels confirmation. HUD clicks and their drag/release are consumed so they do not also interact with chat or inventory underneath. The tracker is rendered above those screens while its controls are usable. Commands remain available.

## Credits

Standalone adaptation maintained by DefinetlyNotRay. Garden plot detection and the settings editor bridge are adapted from SkyHanni and its contributors. The settings screen uses MoulConfig, credited to Moulberry and nea89. The bundled library is relocated to avoid class conflicts; its notices and license texts are included in the JAR. Original source notices and the LGPL-2.1 license remain intact.

The display name is GreenEssentials. The internal mod ID and config/greenhouse folder are retained so existing profiles, settings, and layouts continue to load. Replace the old Greenhouse JAR when upgrading.

## Command aliases

All three roots (`/ge`, `/greenessentials`, `/greenhouse`) execute the same command tree and offer the same completions. Examples: `/ge settings`, `/greenessentials layouts`, `/ge hud`, `/ge session`, `/ge reset session confirm`. Existing `/greenhouse ...` commands continue to work.

| Screen | Short command | Full command | Original command |
| --- | --- | --- | --- |
| Settings | `/gesettings` | `/greenessentialssettings` | `/greenhousesettings` |
| Layout library | `/gelayouts` | `/greenessentialslayouts` | `/greenhouselayouts` |
| HUD editor | `/gehud` | `/greenessentialshud` | `/greenhousehud` |

HUD shortcuts also accept `<x> <y>`. `/shgreenhouseblueprint` remains an alias for the layout library.

About > Used Software lists the reused projects, their licenses, and Source buttons. Session, All-time, and Reset Totals rows are omitted from the settings page; existing HUD controls and commands remain available.

Sack reports are read from original server chat even when another mod hides or rewrites them. A matching confirmed harvest authorizes that item family for the next sack report, for up to 60 seconds; the report consumes that authorization. Inventory still uses its separate two-second harvest window. Moving away, opening a screen, changing profile, or disabling tracking clears both windows. Added and removed sack entries are netted in base units so sack compaction is not treated as income. Inventory credits are retained for 65 seconds to deduplicate delayed sack copies. Same-family transfers mixed into a harvest report cannot be distinguished from harvests by that report alone.

Confirmed All-in Aloe harvests authorize their wheat, sunflower, and moonflower crop rewards in addition to Aloe and fragments. Compacted and enchanted forms are counted in their base crop units. Other crop families still require matching harvest evidence.

## Mutation drop attribution

A bundled mutation-drops.json snapshot supplies crop item identities checked against all 40 Hypixel SkyBlock Wiki mutation pages. Each entry records its source URL and page revision; the file includes a verification date. About > Used Software links to the wiki. Jerryflower explicitly has no crop drops. Unknown future profiles remain unknown; recipes, quantities, and model stage suffixes are not used to invent drop lists. NEU item entries do not currently supply these Greenhouse crop-drop tables.

Manual placements are recorded per profile and plot only after a new matching plant model appears at the clicked cell. Breaking such a plant is treated as returning a placed item, not harvesting a naturally grown mutation. Return credits exclude the item from both inventory and delayed sack gains, including when a legitimate harvest of the same mutation is in the same batch. This handles placing a Blastberry to buff Aloe and then picking it back up. Previously placed plants whose origin was never observed cannot be classified reliably; newly observed placements remain recorded across restarts. This does not prove maturity of a plant whose origin is unknown.

## Source and rebuilding

Source code and build scripts live in this repository. Corresponding bundled dependency source archives, provenance, and instructions for rebuilding with modified MoulConfig are described in [SOURCE-AND-REBUILD.md](SOURCE-AND-REBUILD.md). Do not install the nodeps or sources JAR as the mod.

## Tracker diagnostics

`/ge version` shows the loaded mod and Minecraft versions. `/ge debug` copies a bounded structured event log to the clipboard and saves it to `config/greenhouse/tracker-debug.json`. All full command aliases support these subcommands too.

The log includes confirmed harvests, recognized or ignored targets, authorization resets, item admission/rejection reasons, and the final credited totals. An admitted candidate is not necessarily credited: inventory/sack duplicates and exclusions are applied before totals change. The diagnostic buffer is limited to 512 events and reports how many older entries were dropped. Raw chat, player UUIDs, server addresses, and profile names are not recorded. Nothing is uploaded automatically.

Report the loaded version and reproduce a missed harvest, then run `/ge debug`. Include whether you opened a screen or changed plots before the sack report. Tests in `src/test/resources/replays` are synthetic regressions, not recordings from real Minecraft sessions. See [CONTRIBUTING.md](CONTRIBUTING.md) for architecture and verification conventions.
