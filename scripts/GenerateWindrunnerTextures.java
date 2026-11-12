import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Builds the Windrunner set's textures in the Breeze's palette: the monk robe armor layers and sash
 * (adapted from the mage robe baseline in art/windrunner), the four item icons, the phantom-membrane
 * glide wings, and their breeze-rod ribs. Run from the project root with
 * {@code java scripts/GenerateWindrunnerTextures.java}.
 */
public final class GenerateWindrunnerTextures {
    private static final Path ASSETS = Path.of("src/main/resources/assets/smartmoving/textures");

    private static final int CLEAR = 0x00000000;

    public static void main(String[] args) throws IOException {
        write(humanoid(), "entity/equipment/humanoid/windrunner.png");
        write(leggings(), "entity/equipment/humanoid_leggings/windrunner.png");
        write(membrane(), "entity/windrunner_membrane.png");
        write(rib(), "entity/windrunner_rib.png");
        write(sash(), "entity/windrunner_sash.png");
        write(icon(HEADBAND), "item/windrunner_headband.png");
        write(icon(ROBES), "item/windrunner_robes.png");
        write(icon(PANTS), "item/windrunner_pants.png");
        write(icon(BOOTS), "item/windrunner_boots.png");
    }

    // ---- Armor layers (64 x 32, vanilla humanoid box nets) ----
    //
    // Both layers start from the mage robe in art/windrunner: a white, hand-shaded cloth base and a
    // gold-trim overlay. The base's shading is gradient-mapped onto the Breeze's lavender, the trim
    // becomes its deep-blue band, and the robe is then restyled as a wrapped monk robe.

    private static final Path BASELINE = Path.of("art/windrunner");

    // Vanilla Breeze palette (textures/entity/breeze/breeze.png and breeze_wind.png).
    private static final int B_SHADOW = 0xFF958DD3, B_CLOTH = 0xFFAB9CCB, B_MID = 0xFFB5A5CA, B_LIGHT = 0xFFBFAFC9;
    private static final int B_BAND_LIGHT = 0xFF7A82C4, B_BAND = 0xFF576C9D, B_BAND_DARK = 0xFF3E5E87;
    private static final int B_NAVY = 0xFF32425B, B_NAVY_DARK = 0xFF2B3B52;
    private static final int B_WIND = 0xFFF3F5FF, B_WIND_MID = 0xFFDADCEA, B_WIND_SHADE = 0xFFB9BCD1;
    private static final int B_EYE = 0xFFF9D564;
    private static final int B_HOLLOW = 0xFF26262F, B_VOID = 0xFF19191D;
    // Vanilla breeze rod (textures/item/breeze_rod.png): lavender highlights, periwinkle core, deep shadow.
    private static final int ROD_SHADOW_DEEP = 0xFF2F527F;

    private static BufferedImage humanoid() throws IOException {
        BufferedImage base = ImageIO.read(BASELINE.resolve("mage_base.png").toFile());
        BufferedImage trim = ImageIO.read(BASELINE.resolve("mage_overlay.png").toFile());
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 64; x++) for (int y = 0; y < 32; y++) {
            // The baseline paints a skirt on the leg faces; here only boots may use them, so the
            // skirt moves to the leggings layer. Its hood gives way to the headband.
            boolean leg = x < 16 && y >= 16, head = y < 16;
            if (head) continue;
            if (!leg && opaque(base, x, y)) set(img, x, y, cloth(base.getRGB(x, y)));
            if (opaque(trim, x, y) && (!leg || y >= 27)) set(img, x, y, accent(trim.getRGB(x, y)));
        }
        headband(img);
        wrapFront(img);
        sash(img, 16, 40);
        bootWraps(img);
        return img;
    }

    /**
     * A deep-blue cloth band around the brow, above the eyes, with a white plate on the forehead
     * carrying the Breeze's two yellow eyes and a knot at the back where the 3D tails hang.
     */
    private static void headband(BufferedImage img) {
        for (int x = 0; x < 32; x++) {
            set(img, x, 10, x % 5 == 2 ? B_BAND_LIGHT : B_BAND);
            set(img, x, 11, B_BAND_DARK);
        }
        set(img, 11, 10, B_WIND); set(img, 12, 10, B_WIND_MID);
        set(img, 11, 11, B_EYE); set(img, 12, 11, B_EYE);
        set(img, 10, 10, B_WIND_SHADE); set(img, 13, 10, B_WIND_SHADE);
        for (int x = 27; x <= 28; x++) { set(img, x, 10, B_BAND_LIGHT); set(img, x, 11, B_BAND); set(img, x, 12, B_BAND_DARK); }
    }

    /** Closes the mage's open coat into a right-over-left wrap with a glimpse of the white under-robe. */
    private static void wrapFront(BufferedImage img) {
        for (int x = 21; x <= 26; x++) for (int y = 20; y < 32; y++) {
            set(img, x, y, cloth(0xFF000000 | grey(img, x - 4, y) * 0x010101));
        }
        set(img, 23, 20, B_WIND); set(img, 24, 20, B_WIND_MID); set(img, 24, 21, B_WIND_MID);
        int[][] overlap = {{22, 20}, {23, 21}, {24, 22}, {25, 23}, {26, 24}, {26, 25}};
        for (int[] p : overlap) set(img, p[0], p[1], B_BAND);
        set(img, 25, 20, B_BAND_DARK);
        set(img, 22, 21, B_LIGHT); set(img, 23, 22, B_LIGHT);
        // Below the sash the overlapping panel's edge falls straight to the hem.
        for (int y = 29; y < 32; y++) set(img, 25, y, B_BAND);
    }

    /** A white wind-cloth sash at the waist, knotted left of centre with a Breeze-eye bead. */
    private static void sash(BufferedImage img, int from, int to) {
        for (int x = from; x < to; x++) {
            set(img, x, 27, B_WIND);
            set(img, x, 28, x % 3 == 0 ? B_WIND_SHADE : B_WIND_MID);
        }
        set(img, 22, 27, B_EYE); set(img, 22, 28, B_WIND);
        set(img, 21, 29, B_WIND); set(img, 22, 29, B_WIND_MID);
        set(img, 21, 30, B_WIND_MID); set(img, 22, 30, B_WIND);
        set(img, 22, 31, B_WIND_SHADE);
    }

    /** Navy boots from the baseline's dark leather, with crossing wind-cloth wraps up from the ankle. */
    private static void bootWraps(BufferedImage img) {
        for (int x = 0; x < 16; x++) {
            set(img, x, 27, x % 2 == 0 ? B_WIND : B_WIND_MID);
            if ((x + 1) % 4 == 0) set(img, x, 28, B_WIND_MID);
            if ((x + 3) % 4 == 0) set(img, x, 29, B_WIND_SHADE);
        }
    }

    /** Trousers in the Breeze's deep band, with the robe's split skirt hanging over the outside and back. */
    private static BufferedImage leggings() throws IOException {
        BufferedImage base = ImageIO.read(BASELINE.resolve("mage_base.png").toFile());
        BufferedImage trim = ImageIO.read(BASELINE.resolve("mage_overlay.png").toFile());
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) for (int y = 16; y < 32; y++) {
            int fold = Math.floorMod((x * 7 + y * 3) ^ (x * y), 9);
            set(img, x, y, x % 4 == 1 && y > 21 ? B_BAND_DARK : fold == 0 ? B_BAND_LIGHT : B_BAND);
        }
        for (int x = 8; x < 12; x++) for (int y = 16; y < 20; y++) set(img, x, y, B_BAND_DARK);
        for (int x = 0; x < 16; x++) for (int y = 16; y < 27; y++) {
            if (opaque(base, x, y)) set(img, x, y, cloth(base.getRGB(x, y)));
            if (opaque(trim, x, y)) set(img, x, y, accent(trim.getRGB(x, y)));
        }
        for (int x = 0; x < 16; x++) {
            set(img, x, 27, B_WIND_MID);
            set(img, x, 28, x % 2 == 0 ? B_WIND : B_WIND_MID);
            set(img, x, 29, B_BAND_LIGHT);
            set(img, x, 30, B_BAND);
            set(img, x, 31, B_BAND_DARK);
        }
        // The waistband sits under the robe when both are worn.
        for (int x = 16; x < 40; x++) {
            set(img, x, 27, B_WIND_MID);
            for (int y = 28; y < 32; y++) set(img, x, y, y == 31 ? B_BAND_DARK : B_BAND);
        }
        for (int x = 28; x < 36; x++) for (int y = 16; y < 20; y++) set(img, x, y, B_BAND);
        return img;
    }

    /** Quantizes the baseline's grey cloth shading onto four Breeze lavenders. */
    private static int cloth(int argb) {
        int v = argb & 0xFF;
        return v >= 0xF2 ? B_LIGHT : v >= 0xE2 ? B_MID : v >= 0xCC ? B_CLOTH : B_SHADOW;
    }

    /** Gold trim becomes the deep-blue band by brightness; the dark leather becomes navy. */
    private static int accent(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        if (r > 0xC0 && b < 0x40) return g >= 0xB4 ? B_BAND_LIGHT : g >= 0x9C ? B_BAND : B_BAND_DARK;
        int v = (r + g + b) / 3;
        return v >= 0x40 ? B_BAND_DARK : v >= 0x24 ? B_NAVY : B_NAVY_DARK;
    }

    private static boolean opaque(BufferedImage img, int x, int y) { return (img.getRGB(x, y) >>> 24) > 0; }

    /** Grey level of the baseline pixel used to shade a filled-in patch, from a neighbouring column. */
    private static int grey(BufferedImage img, int x, int y) {
        int c = img.getRGB(x, y);
        if (c == B_LIGHT) return 0xF8;
        if (c == B_MID) return 0xEA;
        if (c == B_CLOTH) return 0xDA;
        return 0xC8;
    }

    // ---- Glide and sash textures ----

    // Vanilla phantom membrane (textures/item/phantom_membrane.png): cream skin, mottling and veins.
    private static final int PHANTOM_CREAM = 0xFFDCD9C0, PHANTOM_SKIN = 0xFFC3B9A1,
            PHANTOM_MOTTLE = 0xFFA99B87, PHANTOM_VEIN = 0xFF8E7F7A;

    /**
     * Phantom-membrane skin with a diagonal vein. The membrane repeats the 6 x 6 tile from texel 5 to
     * 11, so the vein is continuous across tiles; the renderer tints the hems mauve.
     */
    private static final String[] MEMBRANE_TILE = {
            "CCBCCG",
            "CBDBCC",
            "BCCDBC",
            "CCBCDB",
            "GCCBCD",
            "DBCCBC"};

    private static BufferedImage membrane() {
        Map<Character, Integer> cloth = Map.of('C', PHANTOM_CREAM, 'B', PHANTOM_SKIN, 'G', PHANTOM_MOTTLE, 'D', PHANTOM_VEIN);
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) {
            set(img, x, y, cloth.get(MEMBRANE_TILE[Math.floorMod(y - 5, 6)].charAt(Math.floorMod(x - 5, 6))));
        }
        return img;
    }

    /**
     * The breeze rod's own colours, drawn diagonally like the vanilla stick: each column c stacks
     * highlight, core and shadow at rows 15-c to 17-c, alternating the rod's two lavenders and two
     * shadows along the shaft, with a periwinkle knot every fourth texel.
     */
    private static BufferedImage rib() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int c = 2; c < 15; c++) {
            set(img, c, 15 - c, c % 2 == 0 ? B_LIGHT : B_MID);
            set(img, c, 16 - c, c % 4 == 1 ? B_SHADOW : B_BAND_LIGHT);
            if (17 - c < 16) set(img, c, 17 - c, c % 2 == 0 ? ROD_SHADOW_DEEP : B_BAND_DARK);
        }
        return img;
    }

    /** The robe's sash in wind-cloth white, banded with the Breeze's deep blue near each tip. */
    private static BufferedImage sash() {
        BufferedImage img = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 10; x++) for (int y = 0; y < 3; y++) set(img, x, y, B_WIND);
        set(img, 3, 2, B_WIND_SHADE); set(img, 4, 2, B_WIND_SHADE); set(img, 4, 1, B_EYE);
        tail(img, 10, 12, 9);
        tail(img, 16, 10, 7);
        headbandTail(img, 22, 7);
        headbandTail(img, 26, 5);
        return img;
    }

    /** The headband's tails: deep-blue cloth with a lighter edge and a dark frayed tip. */
    private static void headbandTail(BufferedImage img, int u, int height) {
        for (int x = u; x < u + 3; x++) for (int y = 0; y < height; y++) {
            set(img, x, y, y == height - 1 ? B_BAND_DARK : x == u ? B_BAND_LIGHT : B_BAND);
        }
    }

    private static void tail(BufferedImage img, int u, int height, int band) {
        for (int x = u; x < u + 5; x++) for (int y = 0; y < height; y++) {
            int c = y == band ? B_BAND : y == band + 1 ? B_BAND_LIGHT : x == u || x == u + 4 ? B_WIND_MID : B_WIND;
            if (y == height - 1) c = B_WIND_SHADE;
            set(img, x, y, c);
        }
    }

    // ---- Item icons ----

    private static final Map<Character, Integer> PALETTE = Map.ofEntries(
            Map.entry('.', CLEAR), Map.entry('O', B_NAVY_DARK), Map.entry('X', B_VOID), Map.entry('K', B_HOLLOW),
            Map.entry('S', B_SHADOW), Map.entry('B', B_CLOTH), Map.entry('M', B_MID), Map.entry('L', B_LIGHT),
            Map.entry('D', B_BAND_LIGHT), Map.entry('T', B_BAND), Map.entry('F', B_BAND_DARK),
            Map.entry('N', B_NAVY), Map.entry('I', B_HOLLOW), Map.entry('W', B_WIND), Map.entry('w', B_WIND_MID),
            Map.entry('Y', B_EYE));

    private static final String[] HEADBAND = {
            "................",
            "................",
            "................",
            "................",
            "..OOOOOOOOOO....",
            ".OLDDDWWDDDO....",
            ".OTTTTYYTTTOOO..",
            ".OFFFFWWFFFODTO.",
            "..OOOOOOOOOODTO.",
            "...........OTFO.",
            "..........OTFO..",
            "..........OTO...",
            ".........OTFO...",
            ".........OTO....",
            ".........OO.....",
            "................"};

    private static final String[] ROBES = {
            "................",
            "...OOO....OOO...",
            "..OLMTO..OTBSO..",
            ".OLMBBTwwTBBBSO.",
            "OLMBBBBTwTBBBBSO",
            "OLMOBBBBTBBBOBSO",
            "OLMOBBBBBTBBOBSO",
            "OMBOBBBBBBTBOBSO",
            "OTTOBBBBBBBTOTTO",
            "OOOOWWYWWWWWOOOO",
            "...OwWwwwwwwO...",
            "...OBWBBBTBSO...",
            "...OLwBBBTBSO...",
            "...OLBBBBTBSO...",
            "...OOOOOOOOOO...",
            "................"};

    private static final String[] PANTS = {
            "................",
            "..OOOOOOOOOOOO..",
            "..OwWwWwWwWwwO..",
            "..OLBDTTTTTBSO..",
            "..OLBDTTTTFBSO..",
            "..OLBDTTTTFBSO..",
            "..OLBDTOOTFBSO..",
            "..OLBDO..OFBSO..",
            ".OLBDTO..OTFBSO.",
            ".OLBDTO..OTFBSO.",
            ".OLBDTO..OTFBSO.",
            ".OwWwWO..OWwWwO.",
            "..ODTO....OTFO..",
            "..OOOO....OOOO..",
            "................",
            "................"};

    private static final String[] BOOTS = {
            "................",
            "................",
            "................",
            "................",
            "..XXXX....XXXX..",
            "..XwNX....XNwX..",
            "..XNwX....XwNX..",
            "..XwNX....XNwX..",
            "..XWWX....XWWX..",
            "..XFNX....XNNX..",
            ".XFNNX....XNNNX.",
            "XFNNNX....XNNNNX",
            "XIIIIX....XIIIIX",
            "XXXXXX....XXXXXX",
            "................",
            "................"};

    private static BufferedImage icon(String[] rows) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            if (rows[y].length() != 16) throw new IllegalStateException("Icon row " + y + " is not 16 wide");
            for (int x = 0; x < 16; x++) set(img, x, y, PALETTE.get(rows[y].charAt(x)));
        }
        return img;
    }

    // ---- Helpers ----

    private static void set(BufferedImage img, int x, int y, int argb) { img.setRGB(x, y, argb); }

    private static void write(BufferedImage img, String path) throws IOException {
        Path file = ASSETS.resolve(path);
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
        System.out.println("Wrote " + file);
    }
}
