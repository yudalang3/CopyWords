"""Export CopyWords PNG sizes, Windows ICO, and a visual verification sheet."""

from pathlib import Path

from PIL import Image, ImageDraw


PROJECT_ROOT = Path(__file__).resolve().parent.parent
ICON_SIZES = (16, 24, 32, 48, 64, 128, 256)


def main():
    source = Image.open(PROJECT_ROOT / "assets/CopyWords-logo.png").convert("RGBA")
    if source.width != source.height:
        raise ValueError("Logo must be square")
    resource_directory = PROJECT_ROOT / "src/main/resources/cn/copywords/icons"
    resource_directory.mkdir(parents=True, exist_ok=True)
    for size in ICON_SIZES:
        source.resize((size, size), Image.Resampling.LANCZOS).save(
            resource_directory / f"copywords-{size}.png"
        )
    source.save(
        PROJECT_ROOT / "assets/CopyWords.ico",
        format="ICO",
        sizes=[(size, size) for size in ICON_SIZES],
    )
    with Image.open(PROJECT_ROOT / "assets/CopyWords.ico") as icon:
        assert icon.ico.sizes() == {(size, size) for size in ICON_SIZES}
    assert source.getpixel((0, 0))[3] == 0, "Outer background must be transparent"
    preview = Image.new("RGB", (1180, 640), "#f2f4f7")
    drawing = ImageDraw.Draw(preview)
    drawing.rectangle((0, 320, 1180, 640), fill="#202124")
    for index, size in enumerate(ICON_SIZES):
        image = Image.open(resource_directory / f"copywords-{size}.png")
        center = (60, 160, 260, 380, 520, 720, 1000)[index]
        for top, label_color in ((0, "#10243A"), (320, "#f2f4f7")):
            # All sizes are shown at actual pixels on both desktop backgrounds.
            preview.paste(image, (center - size // 2, top + 160 - size // 2), image)
            drawing.text((center - 25, top + 295), f"{size} px", fill=label_color)
    log_directory = PROJECT_ROOT / "logs"
    log_directory.mkdir(exist_ok=True)
    preview.save(log_directory / "icon-size-preview.png")
    print("Exported PNG resources and seven-size Windows ICO")


if __name__ == "__main__":
    main()
