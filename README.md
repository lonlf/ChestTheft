<div align="center">

# ChestTheft

**A chest theft & lock-picking gameplay plugin for Spigot / Paper**

Lock your chests. Pair your keys. And when someone else's chest is in your way —
get out the lockpick and start a minigame.

[![Server](https://img.shields.io/badge/Spigot%20%7C%20Paper-1.19.4%2B-blue)](#requirements)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)](#requirements)
[![Version](https://img.shields.io/badge/version-2.0.1-green)](#)

[中文说明](docs/promotion/README-zh_CN.md) · [Wiki](docs/wiki/README.md) · [Trigger reference](docs/trigger-actions.md)

</div>

---

## What is ChestTheft?

ChestTheft turns "who owns this chest?" into actual gameplay.

Players craft or buy **locks**, **keys** and **lockpicks**. A lock registers a chest
to its owner and stores a secret token. A paired key opens only *that* lock. And a
lockpick doesn't just roll a dice — it drops the player into a **real minigame**:
a moving bar to time, a rhythm sequence to hit, or a two-stage tumbler mechanism to
turn. Succeed and the chest pops open. Fail and you're on cooldown, hoping nobody
heard the noise.

Everything is config-driven: lock difficulty tiers, minigame rules, sounds, item
definitions, messages, and a hook system that lets you fire commands, rewards and
broadcasts at every moment of the interaction.

## Features

### 🔐 Locks, keys and token-bound ownership

- Lock any block in `lock.lockable-blocks` — chests, trapped chests, doors and more.
- Each lock gets a random **token**. A key is bound to *position + token*, so a
  look-alike key won't open your chest.
- `give-key: key001` on a lock definition hands the owner a pre-paired key on the spot.
- Key / lock / picker items can be plain vanilla items with `customModelData`, or be an item from
  **ItemsAdder / Nexo / Oraxen / CraftEngine / NeigeItems / MMOItems / MythicMobs** by writing
  `material: "ItemsAdder:ruby"` (the plugin item's model and NBT are kept).
- Unlock returns the lock item. Re-pair stale keys, or craft a paired key back into
  a blank one to recover from mistakes.
- Doors are treated as a single unit — lock either half and the whole door is locked.
- Hold a matching key and nearby locked chests **glow** and emit particles, so you
  never have to remember which one it was.

### 🎯 Three real lock-picking minigames

| Minigame | What the player does |
| --- | --- |
| `moving-bar` | Click when the cursor crosses the target zone |
| `rhythm-bar` | Hit several target points in sequence — miss one and it's over |
| `tumbler-bar` | Line up tumbler A with `A`/`D`, then turn tumbler B with `W` |

- **10 difficulty tiers** are shipped, each a different mix of minigame × rendering ×
  control scheme. Tiers 0–9 go from "forgiving and slow" to a bitmap-rendered
  five-state tumbler.
- **Two control modes**: the cursor oscillates on its own, or the player is put on an
  invisible mount and steers it manually with `A`/`D`.
- **Two render modes**: a zero-dependency ASCII bar, or **pixel-perfect bitmap
  rendering** driven by the bundled resource pack.
- Picker level subtracts from lock level: `effective difficulty = lock level − picker level`.
- Picking is interrupted by taking damage, walking away, or sneaking to abort.
- Success puts the lock into a **global unlocked** state — either a timed window where
  anyone can open it, or a single one-shot opening. The owner's key (or the owner just
  closing the chest) re-locks it.

### 📦 Loot chests

Turn mob deaths into lootable, locked containers instead of items on the floor.

- Per-mob profiles: match by entity type or by MythicMobs mob, with drop chances.
- Lock loot chests behind a minigame by setting `level` — boss drops can demand a
  tier-9 pick.
- Items can be vanilla materials, your own `items/` definitions, or items from
  **ItemsAdder / Neox / Oraxen / CraftEngine / NeigeItems / MMOItems / MythicMobs**.
- Rendered as **client-side display entities** (no server entities, no client mods) or
  as **real container blocks** that survive restarts.
- Configurable open effects: glow, particles, sound. Configurable expiry for opened
  and unopened chests.

### ⚡ Trigger system

Seven moments, 20 action types, no coding required.

| Triggers | `success` · `fail` · `cancel` · `interrupted` · `lock` · `key-open` · `key-pair` |
| --- | --- |
| **Messages** | `message` · `random-message` · `broadcast` |
| **Commands** | `command` · `player-command` · `random-command` |
| **UI** | `actionbar` · `random-actionbar` · `title` · `random-title` · `close-inv` |
| **Sound** | `sound` |
| **Attributes** | `exp` · `level` · `food` · `saturation` · `potion-effect` |
| **Items** | `give-item` |
| **Logic** | `chain` · `delay` |

- Placeholders: `{player}` `{world}` `{x}` `{y}` `{z}`, plus per-action `chance`.
- Triggers can be **global** (in `trigger/`) or **embedded in a lock item**, so a
  "legendary lock" can fire its own unique reward on every pick attempt.

### 🛡️ Protection plugin compatibility

Locking is denied on chests protected by another plugin — unless you explicitly opt in.
With `protection.picking-enabled: true`, protected chests *can* be picked, and
ChestTheft grants a **zero-window temporary authorization** so the protection plugin
lets the opening through without spamming "you don't have permission".

Supported: **Bolt · LWC · Residence · Dominion · GriefDefender · Towny · WorldGuard · NoBuildPlus**.

- Authorization is granted and revoked inside the event, at `LOWEST` priority — no
  "first click fails, second click works" bugs.
- Persistent grants are written to a `temp_grants` table and cleaned up after a crash.
- Creating a claim, town or region over locked chests automatically removes the locks
  and returns the items, so chests never get sealed inside someone's new land.

### 🧰 Fit and finish

- **SQLite by default, MySQL optional** (HikariCP pooled, with reconnect handling).
- **No client mods required** — glow, display entities and packets are all server-side
  via PacketEvents. Only bitmap minigame rendering needs the resource pack.
- **Bundled resource pack**, auto-extracted on first start, one file for client
  **1.19.4 and above**.
- **Bilingual** (English / Simplified Chinese), with per-message display channel
  (chat / action bar / title).
- Config comments are generated in the language your server runs — and
  `config-version` auto-upgrades on startup, appending new keys instead of
  overwriting your setup.
- Developer API: `ChestLockEvent`, `ChestUnlockEvent`, `ChestKeyOpenEvent`,
  `ChestOpenEvent`, `ChestPickStartEvent`, `ChestInteractEvent`.

## Requirements

| | |
| --- | --- |
| Server | Spigot / Paper **1.19.4+** (API 1.19) |
| Java | **17+** |
| Required | [PacketEvents](https://github.com/retrooper/packetevents) (packet rendering, mounts) |
| Optional | MythicMobs, PlaceholderAPI, ItemsAdder, MMOItems, Nexo, Oraxen, CraftEngine, NeigeItems (custom items in loot chests), Bolt, LWC, Residence, Dominion, GriefDefender, Towny, WorldGuard, NoBuildPlus |

Every optional dependency is genuinely optional — missing plugins disable only their own
feature, and everything else keeps working.

## Installation

```
1. Drop ChestTheft-*.jar into plugins/
2. Install PacketEvents
3. Restart the server
4. /ct reload
```

On first start, ChestTheft generates `plugins/ChestTheft/` with a full commented config,
item definitions, difficulty tiers, loot chest profiles, triggers and language files.

## Try it in 60 seconds

```
/ct give <player> lock001 1
/ct give <player> key001 1
/ct give <player> picker001 1
```

1. **Lock** — hold the lock, left-click a chest (the click is cancelled, so no block
   is harmed).
2. **Pair** — hold the blank key, sneak + left-click the chest.
3. **Open** — hold the paired key, right-click.
4. **Steal** — hold the picker, right-click someone else's locked chest and start picking.
5. **Tune it** — `/ct debug minigame 9` to jump straight into the hardest minigame.

## Commands & permissions

| Command | Description | Permission |
| --- | --- | --- |
| `/ct give <player> <itemId> [amount]` | Give special items | `chesttheft.admin` |
| `/ct setitem <itemId>` | Turn the held item into a special item | `chesttheft.admin` |
| `/ct check` | Inspect the held item's ID, type and pairing | `chesttheft.check` |
| `/ct lootchest <profileId>` | Spawn a loot chest | `chesttheft.admin` |
| `/ct debug minigame <level>` | Play a minigame at a given difficulty | `chesttheft.admin` |
| `/ct debug sound <soundKey> [level]` | Preview a configured sound | `chesttheft.admin` |
| `/ct reload` | Reload all configuration | `chesttheft.admin` |

| Permission | Default | Description |
| --- | --- | --- |
| `chesttheft.use` | `true` | Use the plugin (pick up / interact) |
| `chesttheft.check` | `op` | Use `/ct check` (also allowed with `chesttheft.admin`; grant it to players who should inspect their own keys) |
| `chesttheft.admin` | `op` | Administrative commands |
| `chesttheft.protectionOwner` | `op` | Treated as the owner of any protected chest |

## Documentation

Full documentation lives in [`docs/`](docs/):

| Page | Contents |
| --- | --- |
| [Quick start](docs/wiki/快速开始.md) | Install, run the full lock → pair → open → pick loop |
| [Items & interaction](docs/wiki/物品与交互.md) | Key / lock / picker definitions, interaction keys |
| [External-plugin items](docs/wiki/外部插件物品.md) | Use ItemsAdder / Nexo / Oraxen / CraftEngine / NeigeItems / MMOItems / MythicMobs items as keys, locks, pickers or loot |
| [Minigames](docs/wiki/撬锁玩法.md) | The three minigames, difficulty tiers, sound config |
| [Configuration](docs/wiki/配置详解.md) | Every option in `config.yml` |
| [Trigger system](docs/wiki/触发器系统.md) | Trigger types, actions, placeholders |
| [Loot chests](docs/wiki/战利品箱.md) | Loot chest profiles |
| [Commands & permissions](docs/wiki/命令与权限.md) | Commands, permissions, language, database |
| [Resource pack](docs/wiki/材质包.md) | Bitmap rendering, codepoints, custom skins |
| [Protection compatibility](docs/wiki/保护插件兼容.md) | Integration behaviour per plugin |

## Building

```bash
mvn clean package
```

Output: `target/ChestTheft-2.0.1.jar`. Requires JDK 17.

## License

See [LICENSE](LICENSE).

---

<div align="center">

**ChestTheft** — because a locked chest should be a challenge, not a formality.

</div>
