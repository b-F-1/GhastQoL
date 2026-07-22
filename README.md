# GhastQoL

A Paper plugin for Minecraft **26.2** with quality-of-life tweaks for happy ghasts:
they fly faster (a boost to their `flying_speed` attribute) and, fly to a player when
looking at them. Also adds a rotate lock to make building around Ghasts easier.

## Requirements
- Paper **26.X** (Paper forks like Purpur/Pufferfish work too)
- **JDK 25** (required to build for Minecraft 26.x)

Not supported: Spigot/Bukkit (uses Paper-only APIs), Forge/Fabric (different platform),
and Folia (region-threaded). The plugin runs a startup self-check and disables itself with
a clear log message on Folia or on Minecraft older than 26.

Check your Java version first: `java -version` should report 25. If not, install a
JDK 25 (e.g. Temurin/Adoptium or Azul Zulu) and point `JAVA_HOME` at it.

## Build

This project ships with **both** a Maven `pom.xml` and a Gradle build — use whichever
you prefer (you don't need both).

### Maven
```bash
mvn package
```
The jar lands in `target/ghastqol-1.0.0.jar`. (Maven uses whatever JDK is on
your `JAVA_HOME`/`PATH`, so make sure that's JDK 25.)

The jar lands in `build/libs/GhastQoL-1.0.0.jar`. 

Drop the finished jar in your server's `plugins/` folder and restart.

## Config (`plugins/GhastQoL/config.yml`)
```yaml
speed-multiplier: 3.0     # 3x vanilla flight speed
only-when-ridden: true   # true = only boost while a player is riding

follow:                   # make unridden ghasts follow the nearest player
  enabled: true
  range: 32.0             # only follow players within this many blocks
  stop-distance: 4.0      # stop approaching once this close
  speed: 1.0              # navigation speed multiplier
```

### Following
Each ghast has a **home** (where it's resting — set when it loads, and re-set to wherever
a rider leaves it, mirroring vanilla). It only follows players inside `range` of that home
and won't chase them beyond it; if it drifts out, it steers back home.

**What triggers a follow** depends on `follow.trigger`:
- `look` (default) — the ghast follows whoever is **looking at it** (within `look-angle`
  degrees, and with clear line of sight if `require-line-of-sight` is on). It keeps
  following for `look-linger-seconds` after they look away, so a quick glance elsewhere
  doesn't stop it dead.
- `timer` — the ghast follows on a **cycle**: `follow-seconds` chasing, then `rest-seconds`
  of normal vanilla flight, repeating.

Under the hood it's a custom AI goal (Paper's Mob Goal API) at top priority: while active
it claims the movement slot so the vanilla wander goal can't fight it, and releases it
otherwise. Happy ghasts ignore normal path navigation, so the goal moves them by steering
their velocity toward the target each tick, easing to a hover within `stop-distance`. A
ghast a player is *riding* is always left under the rider's control.

Tuning tips: `range` is the home leash radius; `look-angle` widens/tightens the "looking at
it" cone; `speed` scales glide speed; `stop-distance` is how close it gets.

Reload after editing with `/ghastqol reload` (permission `ghastqol.reload`, default OP).

## Commands
- `/ghastqol reload` — reload the config (permission `ghastqol.reload`, default OP).
- `/ghastqol rotate` — snap the happy ghast you're riding (or the nearest one within
  `rotate.range`) to yaw 0° so it lines up with the block grid. Handy when using a ghast
  as a build platform. Permission `ghastqol.rotate` (default: everyone).

  Note: Minecraft AI can re-rotate a mob, so after aligning, the plugin briefly pauses
  follow for that ghast (`rotate.hold-seconds`) so it stays put. While a player is
  actively *riding and steering*, the rider's input still controls facing — align while
  sitting still, or hop off first.

## How it works
Vanilla happy ghasts have a `flying_speed` base of ~0.05, represented as 1.0 in the config,
which feels sluggish. The plugin adds a scalar attribute modifier so the effective speed
becomes `base × speed-multiplier`. The modifier is applied when a ghast enters the world
(spawn or chunk load) and is cleanly removed if you disable/remove the plugin.

## Notes
- `only-when-ridden` uses mount/dismount events so the ghast is only fast with a
  rider aboard, then returns to normal.
- Very high multipliers can make the ghast hard to steer — 2–4 is a comfortable range.
- This is standard Paper API; for Folia you'd need to swap the scheduler calls
  for the region/entity scheduler.
