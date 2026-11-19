# Research: shinobi-style Windrunner outfit

## The current look

Captured on a mannequin from six sides (the client test camera can't show the local player from
another entity, since vanilla skips drawing the local player then). The outfit is a monk robe
gradient-mapped from the mage robe baseline in `art/windrunner`:

- pale lavender cloth over the whole torso and arms, with a white wind-cloth sash and two long sash
  tails (`WindrunnerSashModel`, 11 and 9 px) trailing from a knot at the back;
- the mage's skirt moved onto the leggings layer, so a lavender skirt hangs over the deep-blue trousers;
- navy boots with white wraps; the deep-blue headband with the Breeze-eye plate and two short tails.

It reads as loose and free-flowing for three reasons: the light cloth with soft folds, the skirt and
long tails, and the chest drawn on vanilla's outer armor model, inflated a full pixel on every side.

## Shinobi direction

Tighter and darker, keeping the Breeze palette so the set still reads as the Breeze's:

- Jacket (chest layer): indigo (the Breeze's navy) fitted top, a lavender collar crossed left over
  right with a glimpse of white under-robe, a narrow lavender obi with the Breeze-eye bead, a white
  Breeze swirl crest between the shoulders, sleeves ending in white forearm wraps that leave the
  hands bare.
- Fit: the robes' chest is drawn on its own armor model inflated 0.65 instead of vanilla's 1.0, still
  just above the leggings' 0.5 so the pants' waistband stays hidden under it. Swapped in through
  `HumanoidArmorLayer.getArmorModel` for the robes only.
- Pants (leggings layer): tight indigo trousers, no skirt.
- Boots: white shin wraps (kyahan) crossing up from split-toe tabi in near-black.
- Headband: unchanged band and eye plate, plus a cloth mask over the nose and mouth.
- Sash model: the long tails become a short obi knot with two small tails in the obi's lavender.
- Item icons restyled to match.

The textures are drawn from scratch in `scripts/GenerateWindrunnerTextures.java`; the mage baseline is
no longer read.

## Results

- Screenshots on a mannequin from six sides and on the player (front, back, sprinting): an indigo
  jacket with the lavender collar crossing to the right hip and a narrow obi with the eye bead, a white
  ring crest on the back, close sleeves ending in white forearm wraps, tight trousers, white shin wraps
  with blue cross-lacing over near-black tabi, and the mask under the headband.
- The fitted chest (0.65) is visibly slimmer at the shoulders and arms than the old 1.0 robe and still
  covers the trousers' waistband.
- First passes drew the wraps as three-tone diagonals, which read as a checkerboard, and the crest as
  an open swirl, which read as a glyph; both were simplified.
- The 3D obi knot and tails show only on players (the mannequin has no glide state), as the sash did.
