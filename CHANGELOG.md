# Changelog

## 1.3.0+1.21.8

Client and server both need 1.3.0: a 1.2.0 client is told to update when it joins a 1.3.0 server.

### New

- **Distant Horizons support**: cloaks and camera screens work with Distant Horizons installed. Its far terrain no
  longer shows through a cloak, its fog no longer covers the camera image, and its clouds show in the views and in the
  cloak's animation.
- **Xaero's Minimap and World Map support**: from outside a cloak, its build no longer shows on the map; the map shows
  the same ground the cloak shows (grass stays grass). It goes off the map and comes back with a nano wave. From
  inside, your base is on the map as always.
- **Walking through a cloak field's wall**: anyone crossing it is cut off at the wall, with a patch of nano pixels
  round the body, seen from inside and outside, in first and third person.
- **Easier wireless array**: drag the extensions into order, click them in the order you want, or let it order them
  (best fit, zigzag or nearest next), with a live 3D preview of the result.

### Changed

- **Simpler GLASS screens**: every GLASS screen has a new, cleaner glass panel look.
- **A cloak field starts at the ground**: with its extensions on posts, the field now reaches down to the ground and
  its height counts from there. Blocks under the ground stay as they are.
- **Silent cloaks**: cloaks and cloak fields switch on and off without a sound.
- Walking into a cloak field shows what it hid at once (the shockwave and its sound are gone).
- Switching a cloak field off while inside it, its walls now sink back down, as they rose.

### Fixed

- A long message at the bottom of a GLASS screen now wraps inside the panel instead of running off it.

## 1.2.0+1.21.8

Client and server both need 1.2.0: a 1.1.0 client is told to update when it joins a 1.2.0 server.

### New

- **Cloaks work with shader packs** (Iris with Complementary, Bliss and others): the cloaked build is gone, and the
  shader draws what lies behind it, shadows included.
- **Nano animation with shader packs**: a cloak field's walls rise as a ring of glowing nano blocks, the build inside
  goes from the ground up behind them and the roof closes in nano; a glass cloak's build turns to nano blocks from the
  projector outwards. The blocks that go stream into the projector as nano pixels, and back out when it switches off.

### Changed

- **Cloak fields are much lighter**: a field that is on no longer draws the world again for every side of it. Its build
  is left out of the world drawing instead (the ground carried on across it), so it costs almost nothing. Switching on
  and off without a shader pack still plays the walls rising and sinking.
- A cloak field now hides its build from every side outside it; inside it, your base is there as always.
- A cloak of normal extension glass keeps showing what lies behind the glass as seen through it (without shaders).

### Fixed

- Seen from right above a cloak field, its roof no longer shows white holes into the void or misses houses around it.
- The top of a cloaked ring of glass no longer shows strips of sky when seen from across it.
- Switching a cloak off no longer shows a blown-up, colourful piece of its picture.
- Flicking a cloak off and straight back on no longer flashes white panels on its glass.

## 1.1.0+1.21.8

Client and server both need 1.1.0: a 1.0.0 client is told to update when it joins a 1.1.0 server.

### Changed

- **Holo Cube at real size**: a scan is now a ball twenty-one blocks across (was fifteen), and the bubble shows it 1:1
  instead of as a two-thirds miniature.
- Scans made with 1.0.0 keep working: they are shown at real size too, in a bubble of their own (smaller) size.
  Nothing has to be scanned again.

### New

- **Cloak fields** react when you go through them: a shockwave of glitchy static runs over the field from where you
  crossed, and the outside ripples when someone walks through or brushes against it (other players too).
- **Version check**: a client with another GLASS version (or none) cannot join a server, and is told which version to
  install. A newer client on an older server is warned that holo cube simulations will not line up.

### Fixed

- A cloak over water no longer shows a hole down to the sea floor.
- A scanned chest in a simulation now always opens when you right-click it, also with an empty hand or an item that
  does something on its own.
- The note at the bottom of a scanned chest's screen no longer runs off the panel.
- A simulation set down in the Nether or the End now shows its scanned sky (sun, moon, stars, colour) inside the bubble.
- GLASS now only loads on Minecraft 1.21.8 itself, instead of also on later 1.21 versions it does not support.

## 1.0.0+1.21.8 — first release of the Fabric fork

The first release of GLASS for Fabric (Minecraft 1.21.8), a fork of iChun's original Forge mod for 1.12.2.
Needs Fabric Loader 0.17+ and Fabric API, on both the client and the server.

### From the original, ported

- GLASS Terminal, Projector, Extension and Wireless Extension: walls of glass become screens showing what a
  far-away terminal sees, spreading across connected glass or floating between wireless extensions.
- No more iChunUtil: the view is a second world pass from the terminal, shown by the glass in screen space.

### New

- **Channels** with owners and passwords (salted, hashed, rate-limited): one camera per channel, search by name or
  owner, the owner can move the camera, change the password or delete the channel. Server settings in
  `config/generallaymansaestheticspyingscreen.json`.
- **Cloak**: a projector in cloak mode shows on its glass what lies behind it, hiding the glass and up to 32 blocks
  behind it. Four wireless extensions in a rectangle raise a **cloak field**: walls and a roof hiding everything inside,
  the ground underneath rebuilt from its surroundings. Cloak amplifiers raise the size limits.
- Cloaks stay hidden at every distance up to the render distance, but cost less further away (smaller, less often,
  only where they are on screen, a few per frame).
- **Scene Selector**: switches a screen between channels, with a wipe across the glass.
- **Hologram kit**: Holo Scanner, Projection Crystal, Holo Projector and Holo Emitters (classic, blue or solid look).
- **Holo Cube**: digitizes the ball of the world around it (fifteen blocks across: blocks, chest contents, signs,
  banners, beds, frames, armor stands, mobs, light, time and weather) and projects it as a walk-in miniature in a
  glowing bubble. The real world is never touched; you can stand on the scan's ground where the world is empty, walk
  through the rest, and look into the scanned chests.
- **GLASS Tome**: an in-game guide with live 3D scenes, recipes and links, given once on first joining; press G over a
  GLASS item to open its page.
- GLASS's own creative tab.
- A sci-fi look for every GLASS screen, glowing edges and a nano grid on screen backs and cloak field insides, GLASS
  sounds, and Blockbench block models.
- **Sodium and Iris**: screens and cloaks are drawn with Sodium's renderer too (Sodium 0.7, Iris without a shader
  pack). With a shader pack on, or another Sodium version, they are not drawn and you are told why; nothing crashes.

### Safety and performance

- The server checks every GLASS packet: reach, channel access, sizes and indices.
- Wrong passwords and new passwords are limited per minute (also across logging out and in).
- A wireless extension only links to a projector within 64 blocks whose channel you may see.
- Holo cube scans are capped in size and sent split up, so a chest full of books cannot kick players; wiped scans
  are deleted from the world's save.
- Leaving a world or server frees every screen's image on the graphics card.

### Known limits

- Gadgets (tablet, hard light, motion sensor, map table, decoy, optical camo) are still in development and switched
  off in this release.
- With an Iris shader pack on, screens show a glow and cloaks plain glass.
