"""Nano Decoy: its animated texture, and the game's models split out of Blockbench's export.

The ball itself is modelled in Blockbench (blockbench/nano_decoy.bbmodel, built through Blockbench's MCP server): a round
ball of stacked sixteen-sided discs (each four flat cubes turned 22.5 degrees apart), gunmetal lit from above, a
glowing band round its middle and a glowing lens on top. It is two halves, "top" and "bottom", which part when it
lands.

    python tools/make_nano_decoy.py texture          writes textures/item/nano_decoy.png (+ .mcmeta: it is animated)
    python tools/make_nano_decoy.py split MODEL.json  splits Blockbench's Java export into the item model and its parts
        (add --parts-only for blockbench/nano_decoy_hex.bbmodel: the hexagons' pieces, no item model)
"""
import json
import math
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/generallaymansaestheticspyingscreen"
TEXTURE = ASSETS / "textures/item/nano_decoy.png"
TEX_ID = "generallaymansaestheticspyingscreen:item/nano_decoy"

# Texture regions (16x16 a frame). SHADE is mapped by height on the ball's sides, so the ball is lit from above.
SHADE = (0, 0, 8, 8)
GLOW = (8, 0, 16, 8)
TOP = (0, 8, 8, 16)
DARK = (8, 8, 16, 16)
FRAMES = 16


def lerp(a, b, t):
    return a + (b - a) * t


def texture():
    """16 frames: the metal stays, the glow pulses with a band of light running across it."""
    from PIL import Image
    img = Image.new("RGBA", (16, 16 * FRAMES), (0, 0, 0, 0))
    px = img.load()
    for f in range(FRAMES):
        rnd = random.Random(11)
        top = 16 * f
        phase = f / FRAMES
        pulse = 0.8 + 0.2 * math.sin(phase * 2 * math.pi)
        band = phase * 12 - 2
        for y in range(8):
            for x in range(8):
                # Sides: gunmetal lit from above (light at the top of the ball, dark at its foot), a thin seam line
                # two rows down, and a faint highlight streak.
                t = y / 7.0
                v = lerp(138, 30, t ** 0.8) + rnd.randint(-3, 3)
                if y == 2:
                    v -= 22
                if x in (2, 3) and y < 5:
                    v += 14
                px[x, top + y] = (int(v * 0.9), int(v * 0.96), int(min(v * 1.1 + 6, 255)), 255)
                # Top faces: a round bright highlight in the middle, darker to the edges.
                d = math.hypot(x - 2.6, y - 2.6) / 6.0
                w = lerp(196, 92, min(d, 1.0)) + rnd.randint(-2, 2)
                px[x, top + 8 + y] = (int(w * 0.9), int(w * 0.97), int(min(w * 1.08 + 6, 255)), 255)
                # Undersides: dark.
                k = 26 + rnd.randint(-2, 2)
                px[8 + x, top + 8 + y] = (k - 3, k, k + 6, 255)
                # Glow: cyan, white-hot in the middle row, pulsing, with a bright band running across.
                mid = 1.0 - abs(y - 3.5) / 4.0
                lift = max(0.0, 1.0 - abs(x - band) / 1.5)
                r = (60 + 120 * mid) * pulse + 90 * lift
                g = (190 + 60 * mid) * pulse + 30 * lift
                px[8 + x, top + y] = (int(min(r, 255)), int(min(g, 255)), 255, 255)
    TEXTURE.parent.mkdir(parents=True, exist_ok=True)
    img.save(TEXTURE)
    TEXTURE.with_suffix(".png.mcmeta").write_text(json.dumps({"animation": {"frametime": 2, "interpolate": True}}, indent=2) + "\n",
                                                  encoding="utf-8")
    print("wrote", TEXTURE)


def split(exported, parts_only=False):
    """Blockbench's Java model: the item model as it is (unless parts_only), plus one model per group
    (nano_decoy_<group>)."""
    model = json.loads(Path(exported).read_text(encoding="utf-8"))
    model["textures"] = {"0": TEX_ID, "particle": TEX_ID}
    elements = model["elements"]
    groups = {g["name"]: set(g["children"]) for g in model.get("groups", []) if isinstance(g, dict)}
    out = {} if parts_only else {"nano_decoy": model}
    for name, children in groups.items():
        out["nano_decoy_" + name] = {"textures": model["textures"], "elements": [e for i, e in enumerate(elements) if i in children]}
    for name, data in out.items():
        data = dict(data)
        for key in ("groups", "credit", "format_version"):
            data.pop(key, None)
        (ASSETS / "models/item" / (name + ".json")).write_text(json.dumps(data, indent=1), encoding="utf-8")
        item = {"model": {"type": "minecraft:model", "model": "generallaymansaestheticspyingscreen:item/" + name}}
        (ASSETS / "items" / (name + ".json")).write_text(json.dumps(item, indent=2) + "\n", encoding="utf-8")
        print("wrote", name, len(data["elements"]), "elements")


if __name__ == "__main__":
    if sys.argv[1] == "texture":
        texture()
    elif sys.argv[1] == "split":
        split(sys.argv[2], "--parts-only" in sys.argv)
