# Windrunner texture sources

`mage_base.png` (white, hand-shaded cloth) and `mage_overlay.png` (gold trim and dark boots) are the
mage robe armor layer supplied as the baseline for the Windrunner set. These design sources are not
included in the mod jar.

`scripts/GenerateWindrunnerTextures.java` gradient-maps the base's grey shading onto four lavenders
from the vanilla Breeze texture, turns the gold trim into the Breeze's deep-blue band and the boots
navy, then restyles the open mage coat as a wrapped monk robe with a white wind-cloth sash. The
baseline's skirt, painted on the leg faces, moves to the leggings layer, since the humanoid layer's
leg faces only render with boots.

From the project root:

```powershell
java scripts/GenerateWindrunnerTextures.java
```
