# BedrockSkinBridge

A Paper plugin that captures Bedrock player skins through Floodgate + the public
GeyserMC skin API and bridges them to Java-side surfaces that the standard
Floodgate setup leaves stuck on Steve — websites, dashboards, and NPC plugins.

## Why this exists

When a Bedrock player joins a Java server through Floodgate, Floodgate tries to
upload their Bedrock skin to MineSkin so the Java client can render it. When
the MineSkin upload fails (rate limit, network hiccup) Floodgate falls back to
a generic "GeyserMC" profile, which is what then leaks through to:

- the player's profile property (`player.getPlayerProfile().getProperties()`),
- third-party renderers that fetch the player's skin by name,
- NPC plugins that call Mojang's session servers (the name doesn't exist there).

In-game, the Bedrock player still looks correct because Geyser re-injects the
real skin via packets — but everywhere else, you see Steve.

BedrockSkinBridge sidesteps the failed-fallback path by reading the real
texture directly from the public GeyserMC skin API
(`api.geysermc.org/v2/skin/<xuid>`) and exposing it to:

- **A small HTTP endpoint** that your website / dashboard can query.
- **FancyNpcs**, by applying the texture value + signature through the API so
  `/npc skin <npc> <bedrock-name>` works without going through Mojang.

## Ghost block fix (new in 1.2.0)

Bedrock clients break and place blocks on their own screen before the server
answers. When a plugin cancels that, for example a land claim, spawn protection
or a minigame rule, a Java client puts the block back by itself, but a Bedrock
client often keeps showing it as broken (or the placed block as there) until
the chunk reloads.

For Bedrock players only, BedrockSkinBridge now sends the real blocks around
the spot again after a cancelled break, place or door/trapdoor/lever click:
once on the next tick and once a few ticks later for slow connections. It also
resyncs the inventory after a cancelled place. Players linked to a Java account
through Floodgate count as Bedrock too.

It does not need any other plugin to know about it. Turn it off in
`config.yml` with `ghost-block-fix.enabled: false`.

## What it does NOT do

- It does not patch in-game player rendering. Geyser already handles that; if
  Bedrock players show Steve in-game, you have a Geyser / Floodgate problem,
  not a BedrockSkinBridge problem.
- It does not upload anything to MineSkin or any third party.
- It does not modify the player's GameProfile on the server.

## Requirements

- **Paper 1.21+** (Spigot probably works too — untested).
- **Java 21+** runtime.
- **[Floodgate](https://geysermc.org/wiki/floodgate/)** — soft dependency, but
  without it the plugin has nothing to capture. Geyser is not required on the
  same host.
- **[FancyNpcs](https://fancyplugins.de/)** — optional. Only needed if you want
  the NPC skin path.

## Installation

1. Drop `BedrockSkinBridge-x.y.z.jar` into your `plugins/` folder.
2. Start the server once to generate `plugins/BedrockSkinBridge/config.yml`.
3. Edit the config if you need to change the HTTP port (default `8082`) or
   disable the HTTP endpoint entirely.
4. Open the firewall for the HTTP port if your website is on a different host.

That's the whole setup. There are no commands and no permissions to configure.

## Configuration (`config.yml`)

```yaml
http:
  enabled: true   # set to false if you only need the FancyNpcs hook
  bind: 0.0.0.0   # interface to bind on
  port: 8082      # firewall must allow this port for off-host consumers

ghost-block-fix:
  enabled: true   # resend blocks to Bedrock players after a cancelled break/place
```

## How it works

On `PlayerJoinEvent`, with a one-second delay so Floodgate has finished
attaching the player's profile:

1. The plugin checks `FloodgateApi.getInstance().isFloodgatePlayer(uuid)`. If
   the player joined through Floodgate, it captures both name forms — the
   Java-side name (e.g. `Melocet~` if you use the username-suffix feature) and
   the bare Bedrock gamertag (e.g. `Melocet`) — so lookups work regardless of
   which form the caller has.
2. It calls `https://api.geysermc.org/v2/skin/<xuid>` asynchronously, parses
   the response, and stores the texture URL plus the signed `value` and
   `signature` strings in memory and in `bedrock-skins.json`.
3. If the GeyserMC API has no record for that XUID (the player has never
   joined a server with GeyserMC's global API enabled, or the API is briefly
   down), the plugin falls back to whatever `player.getPlayerProfile()` has —
   usually the GeyserMC fallback texture, but better than nothing.

The `bedrock-skins.json` file persists across restarts, so a player who joined
once is still served until their next join refreshes the entry.

## HTTP API

```
GET /bedrock-skin?name=<java-side-name>
```

The `name` parameter accepts either the Java-side form (with whatever prefix /
suffix Floodgate is configured to add) or the bare Bedrock gamertag — the
plugin stores under both keys.

Success response:

```json
{
  "name": "Melocet~",
  "texture_url": "http://textures.minecraft.net/texture/d1e3a6b0c659ae91..."
}
```

Failure (no record yet):

```
404 {"error":"not_found"}
```

There is also a `GET /health` endpoint that returns `{"ok":true}` for
monitoring.

### Rendering a face from the texture URL

The texture URL is the raw 64×64 skin sheet, not a face render. If you need a
face, crop the 8×8 region at `(8, 8)` and overlay the 8×8 hat region at
`(40, 8)` on top with alpha blending. A reference PHP implementation
([example](docs/face.php)) is included if you want to drop it into your
website.

## FancyNpcs integration

If FancyNpcs is installed, two paths are activated:

1. **Manual command interception.** Running `/npc skin <npc> <bedrock-name>`
   normally fails because FancyNpcs goes to Mojang and gets a 404 for the
   prefixed/suffixed name. BedrockSkinBridge listens for this command and, if
   the target name is in its cache, applies the captured texture directly via
   the FancyNpcs API, then despawns and respawns the NPC for connected
   clients. The original command is cancelled so Mojang lookup is skipped.

2. **API exposure.** Other plugins that produce dynamic NPCs (leaderboards
   and the like) can call BedrockSkinBridge directly:

   ```java
   var bsb = (BedrockSkinBridge) Bukkit.getPluginManager()
       .getPlugin("BedrockSkinBridge");
   if (bsb != null && bsb.npcSkin().canSkin(uuid, name)) {
       bsb.npcSkin().apply(npcName, uuid, name);
   } else {
       // fall through to /npc skin <name>
   }
   ```

   `canSkin` only returns true when the UUID is a Floodgate UUID (the top 64
   bits are zero), so Java players keep going through the regular Mojang path
   and there's no ambiguity between e.g. a Java `Melocet` and a Bedrock
   `Melocet~`.

Only FancyNpcs is supported out of the box. Adapters for Citizens / ZNPCsPlus
would be welcome PRs.

## Limitations

- A Bedrock player must join the server at least once before their skin is
  available. The plugin does not eagerly pre-fetch.
- The skin shown matches whatever GeyserMC's API has cached for that XUID. If
  the player changes their Bedrock skin and never joins another GeyserMC
  server in between, the cache may be stale — rejoining your own server
  refreshes it.
- The HTTP endpoint is unauthenticated and open. Treat it as public — it
  exposes nothing more sensitive than what's already on Mojang's texture CDN.

## Building from source

```bash
mvn clean package
```

Output ends up at `target/BedrockSkinBridge-<version>.jar`.

## License

MIT. See [LICENSE](LICENSE).
