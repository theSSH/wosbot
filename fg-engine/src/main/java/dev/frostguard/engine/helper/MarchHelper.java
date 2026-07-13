package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.MarchSlotStatus;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.RallyFlagCoordinates;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.vision.ocr.TesseractOcrProvider;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// Handles march-slot availability checks, rally flag interaction,
// and left-panel menu toggling for deployment workflows.
public class MarchHelper {

    private static final int SLOT_COUNT = 6;
    private static final int MAX_FLAG_SLOTS = 8;
    // A padlock matches its own icon at 98-100%; an unlocked slot never exceeds ~37%. Half a slot of
    // tolerance absorbs the tile drift without ever reaching a neighbouring slot (~74px apart).
    private static final double LOCKED_FLAG_THRESHOLD = 85;
    private static final int FLAG_SLOT_TOLERANCE_PX = 35;
    // "Idle" measures ~145 white pixels, a countdown ~255-285, so the gap is wide. Orange "Unlock"
    // (~260) and red "Unavailable" (~565) never overlap white; stationed rows have no status line.
    private static final int COLOUR_PRESENT_MIN = 60;
    private static final int IDLE_WHITE_MAX = 200;
    // Gather icons sit on a green disc (~1000-1200 green pixels); every other icon has none.
    private static final int GATHER_ICON_GREEN_MIN = 300;
    // The returning icon self-matches at 100%; the next closest icon reaches only ~68%.
    private static final double RETURNING_ICON_THRESHOLD = 85;

    private final EmulatorController emu;
    private final String device;
    private final ResilientOcrExecutor<String> ocrStrings;
    private final ProfileContextLogger log;

    public MarchHelper(EmulatorController emuManager, String emulatorNumber,
                       ResilientOcrExecutor<String> stringHelper, AccountDescriptor profile) {
        this.emu = emuManager;
        this.device = emulatorNumber;
        this.ocrStrings = stringHelper;
        this.log = new ProfileContextLogger(MarchHelper.class, profile);
    }

    public boolean checkMarchesAvailable() {
        boolean anyIdle = readMarchQueue().stream().anyMatch(MarchSlotState::isIdle);
        if (!anyIdle) {
            log.info("No idle march slot");
        }
        return anyIdle;
    }

    // Reads every March Queue row from a single screenshot. Text is deliberately avoided: the status
    // line is classified by colour (white "Idle", orange "Unlock", red "Unavailable", nothing at all
    // for stationed troops) and the activity by its icon. Only the countdown needs OCR.
    public List<MarchSlotState> readMarchQueue() {
        openLeftMenuCitySection(false);
        try {
            RawImageData frame = emu.captureScreen(device);
            BufferedImage image = TesseractOcrProvider.toBufferedImage(frame);

            List<MarchSlotState> slots = new ArrayList<>(SLOT_COUNT);
            for (int index = 0; index < SLOT_COUNT; index++) {
                slots.add(readSlot(frame, image, index));
            }
            log.info("March queue: " + slots.stream()
                    .map(slot -> "#" + slot.slot() + "=" + slot.status()
                            + (slot.countdown() == null ? "" : "(" + slot.countdown() + ")"))
                    .collect(Collectors.joining(" ")));
            return slots;
        } catch (Exception ex) {
            log.error("March queue read error: " + ex.getMessage());
            return List.of();
        } finally {
            dismissLeftPanel();
        }
    }

    private MarchSlotState readSlot(RawImageData frame, BufferedImage image, int index) {
        int slot = index + 1;
        AreaData status = CommonGameAreas.MARCH_QUEUE_STATUS[index];

        if (PixelStats.count(image, status, GameColors::isActionOrange) >= COLOUR_PRESENT_MIN
                || PixelStats.count(image, status, GameColors::isBlockedRed) >= COLOUR_PRESENT_MIN)
            return MarchSlotState.of(slot, MarchSlotStatus.LOCKED);

        int white = PixelStats.count(image, status, GameColors::isLabelWhite);
        if (white < COLOUR_PRESENT_MIN)
            return MarchSlotState.of(slot, MarchSlotStatus.STATIONED);
        if (white < IDLE_WHITE_MAX)
            return MarchSlotState.of(slot, MarchSlotStatus.IDLE);

        // A countdown is roughly twice as many white pixels as the word "Idle"; read it.
        Duration countdown = readCountdown(index);
        if (countdown == null)
            return MarchSlotState.of(slot, MarchSlotStatus.BUSY_UNKNOWN);

        AreaData icon = CommonGameAreas.MARCH_QUEUE_ICON[index];
        if (PixelStats.count(image, icon, GameColors::isVividGreen) >= GATHER_ICON_GREEN_MIN)
            return new MarchSlotState(slot, MarchSlotStatus.GATHERING, countdown);
        if (emu.locatePattern(device, frame, TemplatesEnum.MARCH_QUEUE_RETURNING_ICON,
                icon.topLeft(), icon.bottomRight(), RETURNING_ICON_THRESHOLD).isFound())
            return new MarchSlotState(slot, MarchSlotStatus.RETURNING, countdown);

        return new MarchSlotState(slot, MarchSlotStatus.BUSY_UNKNOWN, countdown);
    }

    private Duration readCountdown(int index) {
        AreaData timer = CommonGameAreas.MARCH_QUEUE_TIMER[index];
        String text = ocrStrings.attemptRecognition(timer.topLeft(), timer.bottomRight(),
                2, 150L, CommonOCRSettings.MARCH_QUEUE_TIMER_SETTINGS,
                GameTimeUtils::isAcceptedFormat, value -> value);
        return text == null ? null : GameTimeUtils.parseDuration(text);
    }

    // A locked slot wears a padlock, so it is recognised before it is tapped. The previous check read
    // the unlock prompt with OCR after tapping and treated anything that was not the word "unlock" -
    // garbage included - as a confirmation, which let locked flags through.
    public boolean selectFlag(Integer flagNumber) {
        if (flagNumber == null) {
            log.debug("No flag — skipping");
            return true;
        }
        if (isFlagLocked(flagNumber)) {
            log.warn("Flag #" + flagNumber + " shows a padlock — not selecting it");
            return false;
        }
        log.debug("Selecting flag #" + flagNumber);
        emu.touchPoint(device, RallyFlagCoordinates.pointForFlag(flagNumber));
        interruptibleWait(300);
        return true;
    }

    // Locating every padlock across the strip and mapping each to its nearest slot is immune to the
    // few pixels of tile drift; a per-slot window would leave a 58px template barely any room to slide.
    private boolean isFlagLocked(int flagNumber) {
        int slotX = RallyFlagCoordinates.pointForFlag(flagNumber).getX();
        List<ImageSearchResultData> padlocks = emu.locateAllPatterns(device,
                TemplatesEnum.RALLY_LOCKED_FLAG_SLOT,
                CommonGameAreas.RALLY_FLAG_BAR.topLeft(),
                CommonGameAreas.RALLY_FLAG_BAR.bottomRight(),
                LOCKED_FLAG_THRESHOLD, MAX_FLAG_SLOTS);

        // The multi-hit matcher logs nothing of its own, so record what it saw.
        log.debug("Flag bar: " + padlocks.size() + " padlock(s) located while checking flag #" + flagNumber);

        for (ImageSearchResultData padlock : padlocks) {
            if (Math.abs(padlock.getPoint().getX() - slotX) <= FLAG_SLOT_TOLERANCE_PX) {
                log.info("Flag #" + flagNumber + " padlocked at " + padlock.getPoint()
                        + " score=" + padlock.getMatchScore());
                return true;
            }
        }
        return false;
    }

    public void openLeftMenuCitySection(boolean cityTab) {
        log.debug("Left menu — " + (cityTab ? "city" : "wilderness"));
        emu.touchArea(device, CommonGameAreas.LEFT_MENU_TRIGGER.topLeft(),
                CommonGameAreas.LEFT_MENU_TRIGGER.bottomRight(), 3, 400);
        if (cityTab) {
            emu.touchArea(device, CommonGameAreas.LEFT_MENU_CITY_TAB.topLeft(),
                    CommonGameAreas.LEFT_MENU_CITY_TAB.bottomRight(), 3, 100);
        } else {
            emu.touchArea(device, CommonGameAreas.LEFT_MENU_WILDERNESS_TAB.topLeft(),
                    CommonGameAreas.LEFT_MENU_WILDERNESS_TAB.bottomRight(), 3, 100);
        }
    }

    // Closes the left panel via two sequential touch points.
    public void closeLeftMenu() {
        dismissLeftPanel();
    }

    private void dismissLeftPanel() {
        log.debug("Closing left menu");
        emu.touchPoint(device, CommonGameAreas.LEFT_MENU_CLOSE_CITY);
        interruptibleWait(500);
        emu.touchPoint(device, CommonGameAreas.LEFT_MENU_CLOSE_OUTSIDE);
        interruptibleWait(500);
    }

    private void interruptibleWait(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
