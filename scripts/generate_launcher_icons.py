"""Resize the approved circular artwork into Android launcher resources."""

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "driver_app_icon_circle.png"
RES = ROOT / "app" / "src" / "main" / "res"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def main() -> None:
    with Image.open(SOURCE) as source:
        artwork = source.convert("RGBA")

    # The foreground canvas is 108 dp. Keep the entire circular artwork within
    # the 72 dp launcher mask so no arrow or pin is cropped by adaptive icons.
    foreground = Image.new("RGBA", (1080, 1080), (0, 0, 0, 0))
    circle = artwork.resize((700, 700), Image.Resampling.LANCZOS)
    foreground.alpha_composite(circle, ((1080 - 700) // 2, (1080 - 700) // 2))
    foreground.save(RES / "drawable-nodpi" / "driver_launcher_foreground.png", optimize=True)

    for density, size in SIZES.items():
        target = RES / f"mipmap-{density}"
        target.mkdir(parents=True, exist_ok=True)
        icon = artwork.resize((size, size), Image.Resampling.LANCZOS)
        icon.save(target / "ic_launcher.png", optimize=True)
        icon.save(target / "ic_launcher_round.png", optimize=True)


if __name__ == "__main__":
    main()
