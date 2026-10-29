# GreenEssentials

A client-side Fabric mod for Hypixel SkyBlock's Greenhouse.  
( Please let me know if you want any features added or any bugs that may occur )

Use the **26.1** JAR for Minecraft **26.1 or 26.1.2**. Minecraft 26.2 and 26.3 have separate JARs.

## Features

- **Layout library** : capture, import, export, rotate, and preview Greenhouse layouts, with overlays for missing plants and spawning surfaces.
- **Harvest tracker** : item breakdowns, estimated profit, and separate Session, Daily, and All-time totals for each profile. Daily history keeps the values recorded while tracking.
- **Cost** : confirmed mutation spawner placements reduce net profit. Returning a removed spawner to inventory or sack reverses its original value; returns never count as harvest income. Costs use the tracker price at planting and follow Session, Daily, and All-time views.
- **Customizable HUD** : move and resize the tracker, toggle item icons, and display crops in compacted forms.
- **Appearance settings** : under `/ge` → Layouts, customize highlight color and transparency, toggle crop holograms, crop labels, and spawning surface ghosts, and adjust hologram opacity. Under GUI, customize the tracker's charcoal panel and gold accents; the color picker's alpha slider controls transparency.
- **JellyShort** : shorten or hide jellybean sugar cane model while keeping their hitboxes.
- **Layout sharing** : supports SkyShards, SkyMutations, and SkyLayouts links, plus portable layout files.

Open chat or inventory and click **Price source** below Net profit to cycle Auto, BZ instant buy, BZ instant sell, and NPC. Auto keeps the best instant sell/NPC estimate and manual price overrides. Other sources show only their own quotes, with missing prices marked unpriced. Daily records all four price sources at harvest time, so switching source keeps historical values. Older days retain their original Auto values; sources that were not recorded show as unpriced. Cost stays fixed at its Auto value when planted.

Daily history has no expiry. It is stored in compressed monthly files under `config/greenhouse/history/`, with only a small number of months loaded at once. Only changed months are saved. Existing inline daily history migrates automatically before it is removed from `data.json`; no historical prices are invented during migration.

In Daily view, click the date with chat or inventory open to enter one date or an inclusive range, such as `2026-10-01 to 2026-10-28`. Press Enter to apply or Escape to cancel. The arrows shift the selected day or range by one day; Today returns to the current day.

**Avg/day** appears in Daily and All-time. Daily uses the range's recorded harvest value minus all its costs, divided by the number of days with at least one recorded harvest. All-time uses its totals and harvest-day count since its last reset. Cost-only days reduce the average without increasing that count. With no harvest days, the average shows `-`. Reset applies to the selected day or range and retains history outside it.

## Commands

| Command | Description |
| --- | --- |
| `/ge` | Open settings. |
| `/gelayouts` | Open the layout library. |
| `/gehud` | Move and resize the tracker. |
| `/ge jellyshort` | Open JellyShort controls. |
| `/ge session` | Show this session's harvests. |
| `/ge daily [YYYY-MM-DD [to YYYY-MM-DD]]` | Show today, a specific day, or an inclusive date range, using your local timezone. |
| `/ge alltime` | Show saved harvest totals. |
| `/ge items` | Show the full item breakdown. |
| `/ge stats` | Show estimated harvest value. |
| `/ge reset confirm` | Reset the selected tracker view, keeping layouts. |
| `/ge profile <name>` | Select your SkyBlock profile manually. |
| `/ge debug` | Save and copy tracker diagnostics. |
| `/ge help` | Show all commands. |

`/greenessentials` and `/greenhouse` also work in place of `/ge`.

---

Maintained by DefinetlyNotRay. Contains code adapted from [SkyHanni](https://github.com/hannibal002/SkyHanni), with settings powered by [MoulConfig](https://github.com/NotEnoughUpdates/MoulConfig). [License](LICENSE) · [Third-party credits](THIRD-PARTY-NOTICES.md)
