# Layout templates

Templates for designing collage frames, generated from the same presets the booth prints with
(`DefaultLayoutPresets`), so they cannot drift from the real photo slots.

Output, in `docs/layout-templates/photos-<ratio>/` (one folder per photo ratio of the event: 4:3, 3:2, 16:9):

- `<LAYOUT>_gabarit.png`: the 10×15 cm canvas at 300 dpi; pink photo zones (photo number, size and position in px),
  the blue dashed zone kept free for the title or logo, the red 2 mm trim margin and, for strips, the cut line.
- `<LAYOUT>_masque.png`: white with transparent holes where the photos go, to start a design from.
- `gabarits.pdf`: every layout, one vector page each, at 10×15 cm.

The frame is printed *under* the photos, so anything drawn in a photo zone is hidden. Export the finished frame as a
PNG of the canvas size (1800×1200 landscape, 1200×1800 portrait).

For an event, the per-event templates (with that event's calibration) are on the upload page opened by
*Admin › Layouts & frames › Receive frames by QR code*; frames sent there are applied on the booth automatically.

## Regenerate

After changing a layout in `DefaultLayoutPresets`:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home   # any JDK 17+
LAYOUT_TEMPLATES_OUT=$PWD/tools/layout-templates/layouts.tsv ./gradlew -q :app:testDebugUnitTest --tests '*LayoutTemplateExport*' --rerun
$JAVA_HOME/bin/java tools/layout-templates/LayoutTemplates.java tools/layout-templates/layouts.tsv docs/layout-templates
```

The first command exports the geometry (`layouts.tsv`) from the presets; the second draws the PNGs and PDFs with
plain Java (no dependencies). Copy the three `gabarits.pdf` to the Transat website
(`public/photobooth-templates/gabarits-photos-<ratio>.pdf`) so the upload page links the new version.
