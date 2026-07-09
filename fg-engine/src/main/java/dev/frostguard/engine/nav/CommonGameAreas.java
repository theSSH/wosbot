package dev.frostguard.engine.nav;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;

/**
 * Coordinate catalogue for interactive game regions and OCR zones.
 * All values target the standard 720 × 1280 viewport.
 */
public final class CommonGameAreas {

    private CommonGameAreas() {}

    // ── micro-factories ──────────────────────────────────────────────

    private static AreaData region(int x1, int y1, int x2, int y2) {
        return new AreaData(new PointData(x1, y1), new PointData(x2, y2));
    }

    private static PointData point(int x, int y) {
        return new PointData(x, y);
    }

    // ── account / energy panel ───────────────────────────────────────

    public static final AreaData PROFILE_AVATAR        = region(24, 24, 61, 61);
    public static final AreaData STAMINA_BUTTON        = region(223, 1101, 244, 1123);
    public static final AreaData STAMINA_OCR_AREA      = region(324, 255, 477, 289);
    public static final AreaData SPENT_STAMINA_OCR_AREA = region(540, 1215, 590, 1245);

    // ── side panel navigation ────────────────────────────────────────

    public static final AreaData LEFT_MENU_TRIGGER        = region(8, 538, 8, 560);
    public static final AreaData LEFT_MENU_CITY_TAB       = region(100, 270, 120, 270);
    public static final AreaData LEFT_MENU_WILDERNESS_TAB = region(320, 270, 340, 270);
    public static final PointData LEFT_MENU_CLOSE_CITY    = point(110, 270);
    public static final PointData LEFT_MENU_CLOSE_OUTSIDE = point(463, 548);

    // ── march slot grid (top-left / bottom-right, slot 6→1) ─────────

    public static final PointData[] MARCH_SLOTS_TOP_LEFT = {
            point(189, 740), point(189, 667), point(189, 594),
            point(189, 521), point(189, 448), point(189, 375)
    };

    public static final PointData[] MARCH_SLOTS_BOTTOM_RIGHT = {
            point(258, 768), point(258, 695), point(258, 622),
            point(258, 549), point(258, 476), point(258, 403)
    };

    // ── alliance war controls ────────────────────────────────────────

    public static final AreaData BOTTOM_MENU_ALLIANCE_BUTTON       = region(493, 1187, 561, 1240);
    public static final AreaData ALLIANCE_WAR_RALLY_TAB            = region(81, 114, 195, 152);
    public static final AreaData ALLIANCE_AUTOJOIN_MENU_BUTTON     = region(260, 1200, 450, 1240);
    public static final AreaData ALLIANCE_AUTOJOIN_DISABLE_BUTTON  = region(120, 1069, 249, 1122);

    // ── rally flag & deployment ──────────────────────────────────────

    public static final AreaData FLAG_UNLOCK_TEXT_OCR   = region(297, 126, 424, 168);
    // Starts right of the clock icon (its right edge sits at x 505-507): a sliver of the dial reads
    // as a leading "1" and turns "00:00:39" into an unparsable "100:00:39".
    public static final AreaData TRAVEL_TIME_OCR_AREA   = region(510, 1134, 622, 1162);

    // ── character identity ───────────────────────────────────────────

    public static final AreaData CHARACTER_ID_OCR_AREA   = region(300, 940, 465, 980);
    public static final AreaData CHARACTER_NAME_OCR_AREA = region(280, 890, 600, 930);

    public static final AreaData PROFILE_SETTINGS_BUTTON_AREA =
            region(540, 1150, 720, 1250);
    public static final AreaData PROFILE_SETTINGS_SWITCH_CHARACTER_BUTTON_AREA =
            region(30, 280, 340, 380);
    public static final AreaData PROFILE_SETTINGS_SWITCH_CHARACTER_CHARACTER_LIST_AREA =
            region(60, 380, 660, 1100);
    public static final AreaData PROFILE_SETTINGS_SWITCH_CHARACTER_PROMPT_BUTTON_AREA =
            region(50, 750, 670, 850);
    public static final AreaData PROFILE_SETTINGS_SWITCH_CHARACTER_CONFIRM_DIALOG_NAME_OCR_AREA =
            region(170, 650, 550, 700);

    // ── character name above furnace template (relative offsets) ─────

    public static final int CHARACTER_NAME_ABOVE_FURNACE_TOP_OFFSET_Y    = 60;
    public static final int CHARACTER_NAME_ABOVE_FURNACE_BOTTOM_OFFSET_Y = 10;
    public static final int CHARACTER_NAME_ABOVE_FURNACE_X_START          = 210;
    public static final int CHARACTER_NAME_ABOVE_FURNACE_X_END            = 500;
}
