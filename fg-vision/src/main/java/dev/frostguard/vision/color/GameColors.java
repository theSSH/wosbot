package dev.frostguard.vision.color;

/**
 * The palette the game speaks in: one definition per colour, for everyone.
 *
 * <p>These predicates classify UI text and icons, not artwork. White marks ordinary labels and
 * counters, orange an action the player may still take, red one that is out of reach, and a saturated
 * green a positive state such as an active tick or a resource icon. Widen a threshold here rather than
 * introducing a private variant next to a caller.
 */
public final class GameColors {

    private GameColors() {}

    /** Ordinary white UI text, e.g. "Idle" or a countdown. */
    public static boolean isLabelWhite(int rgb) {
        return red(rgb) > 225 && green(rgb) > 225 && blue(rgb) > 225;
    }

    /** Orange call-to-action text, e.g. an "Unlock" prompt. */
    public static boolean isActionOrange(int rgb) {
        return red(rgb) > 200 && green(rgb) > 120 && green(rgb) < 190 && blue(rgb) < 90;
    }

    /** Red text marking something unavailable or unaffordable. */
    public static boolean isBlockedRed(int rgb) {
        return red(rgb) > 190 && green(rgb) < 100 && blue(rgb) < 100;
    }

    /** Saturated green, e.g. an active tick or a gather icon's disc. */
    public static boolean isVividGreen(int rgb) {
        return green(rgb) > 110 && green(rgb) > red(rgb) + 30 && green(rgb) > blue(rgb) + 30;
    }

    private static int red(int rgb)   { return (rgb >> 16) & 0xFF; }
    private static int green(int rgb) { return (rgb >> 8) & 0xFF; }
    private static int blue(int rgb)  { return rgb & 0xFF; }
}
