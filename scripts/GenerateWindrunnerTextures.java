import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Builds the Windrunner set's textures in the Breeze's palette: the shinobi-style armor layers and obi
 * knot, the four item icons, the phantom-membrane
 * glide wings, their breeze-rod ribs, and the white rings of the glide boost. Run from the project root with
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
        for (int frame = 0; frame < BOOM_FRAMES; frame++) write(boom(frame), "particle/windrunner_boom_" + frame + ".png");
    }

    // ---- Armor layers (64 x 32, vanilla humanoid box nets) ----
    //
    // Fitted shinobi wear in the Breeze's colours: an indigo jacket (the Breeze's navy) with a lavender
    // collar and obi, white wind-cloth wraps on the forearms and shins, and split-toe tabi. Faces are
    // drawn as pixel maps, left to right as the texture lays them out.

    // Vanilla Breeze palette (textures/entity/breeze/breeze.png and breeze_wind.png).
    private static final int B_SHADOW = 0xFF958DD3, B_CLOTH = 0xFFAB9CCB, B_MID = 0xFFB5A5CA, B_LIGHT = 0xFFBFAFC9;
    private static final int B_BAND_LIGHT = 0xFF7A82C4, B_BAND = 0xFF576C9D, B_BAND_DARK = 0xFF3E5E87;
    private static final int B_NAVY = 0xFF32425B, B_NAVY_DARK = 0xFF2B3B52;
    private static final int B_WIND = 0xFFF3F5FF, B_WIND_MID = 0xFFDADCEA, B_WIND_SHADE = 0xFFB9BCD1;
    private static final int B_EYE = 0xFFF9D564;
    private static final int B_HOLLOW = 0xFF26262F, B_VOID = 0xFF19191D;
    // Vanilla breeze rod (textures/item/breeze_rod.png): lavender highlights, periwinkle core, deep shadow.
    private static final int ROD_SHADOW_DEEP = 0xFF2F527F;
    // The indigo cloth: the Breeze's navy, a fold highlight between it and the band, and a seam shadow.
    private static final int INDIGO_LIGHT = 0xFF3B4E6B, INDIGO = B_NAVY, INDIGO_DARK = B_NAVY_DARK, INDIGO_SEAM = 0xFF222D3F;

    private static final Map<Character, Integer> CLOTH = Map.ofEntries(
            Map.entry('.', CLEAR), Map.entry('h', INDIGO_LIGHT), Map.entry('n', INDIGO), Map.entry('d', INDIGO_DARK),
            Map.entry('s', INDIGO_SEAM), Map.entry('L', B_LIGHT), Map.entry('M', B_MID), Map.entry('B', B_CLOTH),
            Map.entry('S', B_SHADOW), Map.entry('W', B_WIND), Map.entry('w', B_WIND_MID), Map.entry('v', B_WIND_SHADE),
            Map.entry('Y', B_EYE), Map.entry('T', B_BAND), Map.entry('D', B_BAND_LIGHT), Map.entry('F', B_BAND_DARK),
            Map.entry('K', B_HOLLOW), Map.entry('X', B_VOID));

    // Jacket front, viewer's left being the wearer's right: the wearer's left panel crosses over to the
    // right hip, its lavender collar meeting the under-panel's at the throat over a white under-robe.
    private static final String[] JACKET_FRONT = {
            "nMWWWWMn",
            "hnMWWMnd",
            "hnnMLnnd",
            "hnMLnnnd",
            "hMLnnnnd",
            "hMLnnnnd",
            "MLnnnnnd",
            "BBYBBBBS",
            "SSSSSSSS",
            "hdnnnnnd",
            "hdnnnnnd",
            "dssddddd"};

    // Jacket back, with a white ring crest round a wind swirl between the shoulders.
    private static final String[] JACKET_BACK = {
            "dnMMMMnh",
            "dnnnnnnh",
            "dnnWWnnh",
            "dnWnvWnh",
            "dnWvnWnh",
            "dnnWWnnh",
            "dnnnnnnh",
            "BBBBBBBB",
            "SSSSSSSS",
            "dnnnsnnh",
            "dnnnsnnh",
            "dddsdddd"};

    private static final String[] JACKET_SIDE = {
            "dnnh",
            "dnnh",
            "dnnh",
            "dnnh",
            "dnnh",
            "dnnh",
            "dnnh",
            "BBBB",
            "SSSS",
            "dnnh",
            "dnnh",
            "dsdd"};

    // Tabi and the white shin wraps above them, by row, for every face of the leg: the top rows are left
    // clear so the trousers show.
    private static final String[] TABI = {
            "....",
            "....",
            "....",
            "FFFF",
            "WWWW",
            "wwww",
            "WWWW",
            "vvvv",
            "DDDD",
            "KKKK",
            "KKKK",
            "XXXX"};

    private static BufferedImage humanoid() {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        jacket(img);
        sleeves(img);
        tabi(img);
        headband(img);
        return img;
    }

    private static void jacket(BufferedImage img) {
        paint(img, 20, 20, JACKET_FRONT);
        paint(img, 32, 20, JACKET_BACK);
        paint(img, 16, 20, JACKET_SIDE);
        paint(img, 28, 20, JACKET_SIDE);
        // Shoulders, with the collar running round the neck; the hem underneath.
        for (int x = 20; x < 28; x++) for (int y = 16; y < 20; y++) {
            boolean collar = x >= 22 && x <= 25 && (y == 16 || y == 19);
            set(img, x, y, collar ? B_MID : x == 20 ? INDIGO_LIGHT : INDIGO);
        }
        for (int x = 28; x < 36; x++) for (int y = 16; y < 20; y++) set(img, x, y, INDIGO_DARK);
    }

    /**
     * Close indigo sleeves to the elbow, a lavender hem, then white wraps wound diagonally round the
     * forearm. The last two rows and the underside stay clear so the hands show.
     */
    private static void sleeves(BufferedImage img) {
        for (int x = 44; x < 48; x++) for (int y = 16; y < 20; y++) set(img, x, y, INDIGO);
        for (int x = 40; x < 56; x++) {
            int face = (x - 40) / 4;
            for (int y = 20; y < 25; y++) set(img, x, y, face == 0 ? INDIGO_LIGHT : face == 2 ? INDIGO_DARK : INDIGO);
            set(img, x, 25, B_SHADOW);
            for (int y = 26; y < 30; y++) {
                boolean edge = Math.floorMod(x + y, 4) == 0;
                set(img, x, y, edge ? B_WIND_SHADE : y % 2 == 0 ? B_WIND : B_WIND_MID);
            }
        }
    }

    /** Boots: split-toe tabi in near-black with the Breeze-blue straps and white shin wraps above. */
    private static void tabi(BufferedImage img) {
        for (int face = 0; face < 4; face++) paint(img, face * 4, 20, TABI);
        // Blue cross-lacing up the front of the shin wraps.
        String[] lacing = {"T..T", ".TT.", ".TT.", "T..T"};
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) if (lacing[y].charAt(x) == 'T') set(img, 4 + x, 24 + y, B_BAND);
        // The front's split between the big toe and the rest.
        set(img, 5, 30, B_VOID); set(img, 5, 31, B_VOID);
        for (int x = 8; x < 12; x++) for (int y = 16; y < 20; y++) set(img, x, y, B_VOID);
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

    /** Close-fitting indigo trousers; the waistband sits under the jacket when both are worn. */
    private static BufferedImage leggings() {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) {
            int face = x / 4;
            for (int y = 20; y < 32; y++) {
                boolean fold = (face == 1 && y == 25 && x != 4) || (face == 3 && y == 26);
                set(img, x, y, fold ? INDIGO_SEAM : face == 0 ? INDIGO_LIGHT : face == 2 ? INDIGO_DARK : INDIGO);
            }
        }
        for (int x = 4; x < 8; x++) for (int y = 16; y < 20; y++) set(img, x, y, INDIGO);
        for (int x = 8; x < 12; x++) for (int y = 16; y < 20; y++) set(img, x, y, INDIGO_DARK);
        for (int x = 16; x < 40; x++) {
            set(img, x, 27, B_BAND_DARK);
            for (int y = 28; y < 32; y++) set(img, x, y, y == 31 ? INDIGO_SEAM : INDIGO_DARK);
        }
        for (int x = 28; x < 36; x++) for (int y = 16; y < 20; y++) set(img, x, y, INDIGO_DARK);
        return img;
    }

    private static void paint(BufferedImage img, int u, int v, String[] rows) {
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) set(img, u + x, v + y, CLOTH.get(rows[y].charAt(x)));
        }
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

    /** The jacket's obi knot and its two short tails in the obi's lavender, beside the headband's tails. */
    private static BufferedImage sash() {
        BufferedImage img = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 10; x++) for (int y = 0; y < 3; y++) set(img, x, y, y == 2 ? B_SHADOW : x % 5 == 2 ? B_MID : B_CLOTH);
        set(img, 4, 1, B_EYE);
        tail(img, 10, 7);
        tail(img, 16, 6);
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

    private static void tail(BufferedImage img, int u, int height) {
        for (int x = u; x < u + 5; x++) for (int y = 0; y < height; y++) {
            set(img, x, y, y == height - 1 ? B_SHADOW : x == u || x == u + 4 ? B_MID : B_CLOTH);
        }
    }

    // ---- Item icons ----

    private static final Map<Character, Integer> PALETTE = Map.ofEntries(
            Map.entry('.', CLEAR), Map.entry('O', B_NAVY_DARK), Map.entry('X', B_VOID), Map.entry('K', B_HOLLOW),
            Map.entry('S', B_SHADOW), Map.entry('B', B_CLOTH), Map.entry('M', B_MID), Map.entry('L', B_LIGHT),
            Map.entry('D', B_BAND_LIGHT), Map.entry('T', B_BAND), Map.entry('F', B_BAND_DARK),
            Map.entry('N', B_NAVY), Map.entry('I', B_HOLLOW), Map.entry('W', B_WIND), Map.entry('w', B_WIND_MID),
            Map.entry('Y', B_EYE), Map.entry('h', INDIGO_LIGHT), Map.entry('n', INDIGO), Map.entry('d', INDIGO_DARK),
            Map.entry('s', INDIGO_SEAM), Map.entry('v', B_WIND_SHADE));

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
            "....XXXXXXXX....",
            "..XXnMWWWWMnXX..",
            ".XhnnnMWWMnnndX.",
            "XhnnnnnMLnnnnndX",
            "XhnnnnMLnnnnnndX",
            "XhnXnMLnnnnnXndX",
            "XhnXMLnnnnnnXndX",
            "XwwXBBYBBBBBXwwX",
            "XWvXSSSSSSSSXvWX",
            "...XhdnnnnnnX...",
            "...XhdnnnnnnX...",
            "...XXXXXXXXXX...",
            "................",
            "................",
            "................"};

    private static final String[] PANTS = {
            "................",
            "...XXXXXXXXXX...",
            "...XFFFFFFFFX...",
            "...XhnnnnnndX...",
            "...XhnnnnnndX...",
            "...XhnnXXnndX...",
            "...XhnX..XndX...",
            "...XhnX..XndX...",
            "...XhnX..XndX...",
            "...XhnX..XndX...",
            "...XhdX..XddX...",
            "...XXXX..XXXX...",
            "................",
            "................",
            "................",
            "................"};

    private static final String[] BOOTS = {
            "................",
            "................",
            "................",
            "................",
            "..XXXX....XXXX..",
            "..XWTX....XTWX..",
            "..XTWX....XWTX..",
            "..XWTX....XTWX..",
            "..XDDX....XDDX..",
            "..XKKX....XKKX..",
            ".XKKKX....XKKKX.",
            "XKKXKX....XKXKKX",
            "XKKXKX....XKXKKX",
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

    // ---- Boost rings (32 x 32, eight frames for particles/windrunner_boom.json) ----
    //
    // Original white artwork for the glide boost's shockwave: unlike the Warden's sonic boom, which
    // converges for half its frames before it bursts, these start at the burst, a small thick ring
    // that opens into a double ring and breaks up as it spreads.

    private static final int BOOM_FRAMES = 8;
    private static final int BOOM_WHITE = 0xFFFFFFFF, BOOM_SOFT = 0xFFDCE6EC;

    private static BufferedImage boom(int frame) {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        switch (frame) {
            // Thin bands throughout: the booster flies through these, so a thick one would white out the view.
            case 0 -> ring(img, 4.5, 2, BOOM_WHITE, 0, 0);
            case 1 -> { ring(img, 7.5, 1.8, BOOM_WHITE, 0, 0); ring(img, 10, 1, BOOM_SOFT, 0, 0); }
            case 2 -> { ring(img, 6.5, 1, BOOM_SOFT, 0, 0); ring(img, 9.5, 1.5, BOOM_WHITE, 0, 0); ring(img, 12, 1, BOOM_SOFT, 0, 0); }
            case 3 -> { ring(img, 11, 1.5, BOOM_WHITE, 0, 0); ring(img, 13.5, 1, BOOM_SOFT, 0, 0); }
            case 4 -> ring(img, 12.5, 1.4, BOOM_WHITE, 0, 0);
            case 5 -> ring(img, 13.5, 1.2, BOOM_WHITE, 5, -0.6);
            case 6 -> ring(img, 14.2, 1.1, BOOM_SOFT, 3, 0.1);
            default -> ring(img, 14.8, 1, BOOM_SOFT, 2, 0.6);
        }
        return img;
    }

    /**
     * A pixel ring of the given radius and width around the texture centre. With {@code breaks} above
     * zero it keeps only the arcs where {@code sin(angle * breaks + 1) > gap}, so it reads as breaking up.
     */
    private static void ring(BufferedImage img, double radius, double width, int argb, int breaks, double gap) {
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                double dx = x - 15.5, dy = y - 15.5;
                if (Math.abs(Math.sqrt(dx * dx + dy * dy) - radius) > width / 2) continue;
                if (breaks > 0 && Math.sin(Math.atan2(dy, dx) * breaks + 1) <= gap) continue;
                set(img, x, y, argb);
            }
        }
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
