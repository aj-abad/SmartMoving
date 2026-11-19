# Windrunner texture sources

`mage_base.png` (white, hand-shaded cloth) and `mage_overlay.png` (gold trim and dark boots) are the
mage robe armor layer supplied as the baseline for the Windrunner set. These design sources are not
included in the mod jar.

The original monk robe was gradient-mapped from this baseline. Since the shinobi restyle,
`scripts/GenerateWindrunnerTextures.java` draws the armor layers from scratch and no longer reads these
files; they are kept for reference.

From the project root:

```powershell
java scripts/GenerateWindrunnerTextures.java
```
