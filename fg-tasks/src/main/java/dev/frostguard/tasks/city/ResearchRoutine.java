package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.ResearchCategoryEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.PriorityItemData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.config.PriorityConfigResolver;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.sourceforge.tess4j.TesseractException;

public class ResearchRoutine extends DelayedTask {

private static final int HAND_CLICK_OFFSET_X_VALUE = -73;

private static final int HAND_CLICK_OFFSET_Y_VALUE = 88;

private static final int RESEARCH_CLICK_OFFSET_X_VALUE = -3;

private static final int RESEARCH_CLICK_OFFSET_Y_VALUE = -54;

private static final int MAX_SCROLL_ATTEMPTS_LIMIT = 10;

public ResearchRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

@Override
    protected void execute() {


        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);


        marchHelper.openLeftMenuCitySection(true);


        logDebug(routineLogResearchLine("Inspecting research queue status via OCR..."));
        try {
            String queueStatus = emuManager.readText(
                    EMULATOR_NUMBER,
                    new PointData(164, 811),
                    new PointData(303, 841)).trim();

            logInfo(routineLogResearchLine("Research queue OCR status: '" + queueStatus + "'"));

            if (!queueStatus.toLowerCase().contains("idle")) {


                logInfo(routineLogResearchLine("Research queue is busy. Attempting to read remaining time..."));
                Duration busyTime = durationHelper.attemptRecognition(
                        new PointData(164, 811),
                        new PointData(303, 841),
                        5,
                        300,
                        null,
                        GameTimeUtils::isAcceptedFormat,
                        GameTimeUtils::parseDuration);

                if (busyTime != null) {
                    long minutesToWait = busyTime.toMinutes();
                    LocalDateTime rescheduleTime;

                    if (minutesToWait > 30) {
                        long halfTime = minutesToWait / 2;
                        rescheduleTime = LocalDateTime.now().plusMinutes(halfTime);
                        logInfo(routineLogResearchLine("Research busy for " + minutesToWait + " min. Planning next run at half time: " + halfTime
                                + " min from now."));
                    } else {
                        rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                        logInfo(routineLogResearchLine("Research busy for " + minutesToWait + " min. Planning next run at: " + minutesToWait
                                + " min from now."));
                    }

                    this.reschedule(rescheduleTime);
                } else {
                    logWarning(routineLogResearchLine("Could not read research queue time. Planning next run in 1 hour."));
                    this.reschedule(LocalDateTime.now().plusHours(1));
                }
                return;
            }
        } catch (IOException | TesseractException | RuntimeException e) {
            logError(routineLogResearchLine("Issue while research status OCR: " + e.getMessage()));
            this.reschedule(LocalDateTime.now().plusHours(1));
            return;
        }

        logInfo(routineLogResearchLine("Research queue is Idle. Proceeding..."));


        ImageSearchResultData researchCenter = templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_SHORTCUTS_RESEARCH_CENTER,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (!researchCenter.isFound()) {
            logError(routineLogResearchLine("Research Center shortcut not detected."));
            return;
        }

        logDebug(routineLogResearchLine("Pressing Research Center"));
        tapPoint(researchCenter.getPoint());
        sleepTask(1000);


        RawImageData screenshot = emuManager.captureScreen(EMULATOR_NUMBER);

        if (screenshot != null) {
            ImageSearchResultData result = emuManager.locatePattern(EMULATOR_NUMBER, screenshot,
                    TemplatesEnum.SKIP_TUTORIAL_HAND, 80.0);
            ImageSearchResultData mirrorResult = emuManager.locatePattern(EMULATOR_NUMBER, screenshot,
                    TemplatesEnum.SKIP_TUTORIAL_HAND_MIRROR, 80.0);

            if ((result != null && result.isFound()) || (mirrorResult != null && mirrorResult.isFound())) {
                logInfo(routineLogResearchLine("Hand template or mirror detected! Pressing it with offset."));
                PointData adjustedPoint;
                if (result != null && result.isFound()) {
                    PointData handPoint = result.getPoint();
                    adjustedPoint = new PointData(handPoint.getX() + HAND_CLICK_OFFSET_X_VALUE,
                            handPoint.getY() + HAND_CLICK_OFFSET_Y_VALUE);
                } else {


                    PointData handPoint = mirrorResult.getPoint();
                    adjustedPoint = new PointData(handPoint.getX() - HAND_CLICK_OFFSET_X_VALUE,
                            handPoint.getY() + HAND_CLICK_OFFSET_Y_VALUE);
                }
                tapPoint(adjustedPoint);
                sleepTask(300);
            }
        }
        sleepTask(300);


        ImageSearchResultData researchTextResult = findResearchInPriorityCategories();
        if (researchTextResult == null) {
            logWarning(routineLogResearchLine("No enabled category had an available research. Planning next run in 1 hour."));
            this.reschedule(LocalDateTime.now().plusHours(1));
            return;
        }

        logInfo(routineLogResearchLine("Research text template detected."));


                tapPoint(researchTextResult.getPoint());
                sleepTask(500);


                try {
                    String confirmText = emuManager.readText(
                            EMULATOR_NUMBER,
                            new PointData(545, 1171),
                            new PointData(660, 1216)).trim();
                    logInfo(routineLogResearchLine("Research confirm button OCR text: '" + confirmText + "'"));

                    if (!confirmText.toLowerCase().contains("speedup")) {


                        tapPoint(new PointData(600, 1190));
                        sleepTask(1000);
                    } else {
                        logInfo(routineLogResearchLine("Button says 'Speedup'. Skipping click to safely read remaining time."));
                    }
                } catch (Exception e) {
                    logWarning(routineLogResearchLine("Error OCRing confirm button: " + e.getMessage()));


                    tapPoint(new PointData(600, 1190));
                    sleepTask(1000);
                }


                logInfo(routineLogResearchLine("Reading research time via OCR..."));
                Duration researchTime = durationHelper.attemptRecognition(
                        new PointData(226, 1194),
                        new PointData(422, 1234),
                        5,
                        300,
                        null,
                        GameTimeUtils::isAcceptedFormat,
                        GameTimeUtils::parseDuration);


                if (researchTime != null) {
                    long minutesToWait = researchTime.toMinutes();
                    LocalDateTime rescheduleTime;

                    if (minutesToWait > 30) {
                        long halfTime = minutesToWait / 2;
                        rescheduleTime = LocalDateTime.now().plusMinutes(halfTime);
                        logInfo(routineLogResearchLine("Research time exceeds 30 minutes (" + minutesToWait
                                + " min). Planning next run for half time: " +
                                halfTime + " minutes from now"));
                    } else if (minutesToWait < 5) {
                        if (minutesToWait == 0) {
                            minutesToWait = 1;
                        }
                        rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                        logInfo(routineLogResearchLine("Research time is less than 5 minutes. Keeping normal schedule: " +
                                minutesToWait + " minutes from now"));
                    } else {
                        if (minutesToWait == 0) {
                            minutesToWait = 1;
                        }
                        rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                        logInfo(routineLogResearchLine("Research time is " + minutesToWait + " minutes. Using normal schedule"));
                    }

                    logInfo(routineLogResearchLine("Research task completed. Planning next run for: " + rescheduleTime));
                    this.reschedule(rescheduleTime);
                    return;
                } else {
                    logWarning(routineLogResearchLine("Could not OCR research time. Falling back to 1 hour reschedule."));
                }

        this.reschedule(LocalDateTime.now().plusHours(1));
    }

private ImageSearchResultData findResearchInPriorityCategories() {
        List<PriorityItemData> priorities = PriorityConfigResolver.activeRankings(
                profile, ConfigurationKeyEnum.RESEARCH_PRIORITIES_STRING);
        if (priorities.isEmpty()) {
            logWarning(routineLogResearchLine("No research categories enabled."));
            return null;
        }

        for (PriorityItemData priority : priorities) {
            ResearchCategoryEnum category = ResearchCategoryEnum.fromKey(priority.getIdentifier());
            if (category == null) {
                logWarning(routineLogResearchLine("Ignoring unknown research category priority: " + priority.getIdentifier()));
                continue;
            }

            logInfo(routineLogResearchLine("Trying category '" + category.label()
                    + "' (priority " + priority.getPriority() + ")."));
            tapCategoryTab(category);
            sleepTask(500);

            if (!findAndTapResearchNode()) {
                logInfo(routineLogResearchLine("No available tech node in '" + category.label()
                        + "'. Trying next category."));
                continue;
            }

            sleepTask(1000);
            RawImageData researchTextScreenshot = emuManager.captureScreen(EMULATOR_NUMBER);
            ImageSearchResultData candidate = researchTextScreenshot == null ? null
                    : emuManager.locatePattern(EMULATOR_NUMBER, researchTextScreenshot,
                            TemplatesEnum.RESEARCH_TEXT, 80.0);
            if (candidate != null && candidate.isFound()) {
                logInfo(routineLogResearchLine("'" + category.label() + "' has an available research."));
                return candidate;
            }

            logInfo(routineLogResearchLine("'" + category.label()
                    + "' node did not open a research. Trying next category."));
            pressBack();
            sleepTask(500);
        }
        return null;
    }

private void tapCategoryTab(ResearchCategoryEnum category) {
        switch (category) {
            case GROWTH -> tapRandomPoint(new PointData(58, 88), new PointData(211, 137));
            case ECONOMY -> tapRandomPoint(new PointData(274, 84), new PointData(445, 142));
            case BATTLE -> tapRandomPoint(new PointData(499, 99), new PointData(671, 139));
        }
    }

private boolean findAndTapResearchNode() {
        logDebug(routineLogResearchLine("Normalizing research menu with swipes..."));
        for (int i = 0; i < 3; i++) {
            swipe(new PointData(489, 320), new PointData(489, 1156));
            sleepTask(500);
        }

        TemplatesEnum[] researchTemplates = {
                TemplatesEnum.RESEARCH_0_3,
                TemplatesEnum.RESEARCH_1_3,
                TemplatesEnum.RESEARCH_2_3
        };

        for (int scrollAttempt = 0; scrollAttempt < MAX_SCROLL_ATTEMPTS_LIMIT; scrollAttempt++) {
            checkPreemption();
            sleepTask(500);

            RawImageData researchScreenshot = emuManager.captureScreen(EMULATOR_NUMBER);
            if (researchScreenshot == null) {
                logWarning(routineLogResearchLine("Could not capture screenshot for research template search."));
                continue;
            }

            List<ImageSearchResultData> foundResults = new ArrayList<>();
            for (TemplatesEnum template : researchTemplates) {
                // Single-hit search would return only the single best-correlated occurrence
                // across the whole screen. An untouched tree renders several nodes with the
                // identical "X/3" badge at once, so it must find ALL of them here for
                // "topmost of foundResults" below to actually mean the topmost node on screen
                // instead of an arbitrary same-looking match.
                List<ImageSearchResultData> templateResults = emuManager.locateAllPatterns(
                        EMULATOR_NUMBER, researchScreenshot, template,
                        new PointData(0, 0), new PointData(720, 1280), 90.0, 10);
                if (!templateResults.isEmpty()) {
                    logInfo(routineLogResearchLine("Detected " + templateResults.size()
                            + " node(s) with template: " + template.name()));
                    foundResults.addAll(templateResults);
                }
            }

            if (!foundResults.isEmpty()) {
                ImageSearchResultData highest = foundResults.stream()
                        .min(Comparator.comparingInt(r -> r.getPoint().getY()))
                        .get();
                logInfo(routineLogResearchLine("Pressing research template at position: ("
                        + highest.getPoint().getX() + ", " + highest.getPoint().getY() + ") with offset"));

                PointData researchPoint = highest.getPoint();
                tapPoint(new PointData(researchPoint.getX() + RESEARCH_CLICK_OFFSET_X_VALUE,
                        researchPoint.getY() + RESEARCH_CLICK_OFFSET_Y_VALUE));
                sleepTask(300);
                return true;
            }

            logDebug(routineLogResearchLine("Zero research templates detected, scrolling up (attempt "
                    + (scrollAttempt + 1) + "/" + MAX_SCROLL_ATTEMPTS_LIMIT + ")"));
            swipe(new PointData(489, 800), new PointData(489, 300));
        }
        return false;
    }

private String routineLogResearchLine(String note) {
        return "ResearchRoutine | " + note;
    }
}
