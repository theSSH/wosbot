package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.*;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import static dev.frostguard.api.configs.TemplatesEnum.BUILDING_BUTTON_INFO;
import static dev.frostguard.api.configs.TemplatesEnum.BUILDING_BUTTON_SPEED;
import static dev.frostguard.api.configs.TemplatesEnum.BUILDING_BUTTON_TRAIN;
import static dev.frostguard.api.configs.TemplatesEnum.BUILDING_BUTTON_UPGRADE;
import static dev.frostguard.api.configs.TemplatesEnum.BUILDING_SURVIVOR_BUTTON_UPGRADE;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_SHORTCUTS_HELP_REQUEST4;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_SHORTCUTS_OBTAIN;
import static dev.frostguard.engine.nav.ButtonConstants.*;
import static dev.frostguard.engine.nav.LeftMenuTextSettings.*;

public class UpgradeBuildingsRoutine extends DelayedTask {

private static final AreaData QUEUE_AREA_1_VALUE = new AreaData(new PointData(95, 377), new PointData(358, 398));

private static final AreaData QUEUE_AREA_2_VALUE = new AreaData(new PointData(95, 450), new PointData(358, 474));

private static final AreaData BUILDING_ACTION_BUTTON_AREA_VALUE = new AreaData(new PointData(190, 1160), new PointData(530, 1250));

private static final AreaData BUILDING_CONFIRM_BUTTON_AREA_VALUE = new AreaData(new PointData(489, 1034), new PointData(500, 1050));

private final List<AreaData> queues = new ArrayList<>(Arrays.asList(QUEUE_AREA_1_VALUE, QUEUE_AREA_2_VALUE));

public UpgradeBuildingsRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTaskEnum) {
        super(profile, tpDailyTaskEnum);
    }

@Override
    protected void execute() {


        reachCityView();


        List<UpgradeBuildingsRoutine.QueueReadout> queueResults = inspectAllQueues();


        logQueueSummaryFlow(queueResults);


        boolean hasIdleQueue = queueResults.stream()
                .anyMatch(result -> result.state.status == UpgradeBuildingsRoutine.QueueMood.IDLE ||
                        result.state.status == UpgradeBuildingsRoutine.QueueMood.IDLE_TEMP);

        if (hasIdleQueue) {


            List<LocalDateTime> troopTrainingTimes = new ArrayList<>();


            for (UpgradeBuildingsRoutine.QueueReadout result : queueResults) {
                if (result.state.status == UpgradeBuildingsRoutine.QueueMood.IDLE ||
                        result.state.status == UpgradeBuildingsRoutine.QueueMood.IDLE_TEMP) {
                    logInfo(routineLogUpgradeBuildingsLine("Processing queue " + result.queueNumber + " (Status: " + result.state.status + ")"));
                    LocalDateTime trainingTime = handleQueue(result);


                    if (trainingTime != null) {
                        troopTrainingTimes.add(trainingTime);
                    }
                }
            }

            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);

            logInfo(routineLogUpgradeBuildingsLine("Reanalyzing queues after processing idle queues..."));

            reachCityView();


            List<UpgradeBuildingsRoutine.QueueReadout> updatedResults = inspectAllQueues();


            logInfo(routineLogUpgradeBuildingsLine("=== Updated Queue Analysis After Processing ==="));
            logQueueSummaryFlow(updatedResults);


            if (!troopTrainingTimes.isEmpty()) {
                logInfo(routineLogUpgradeBuildingsLine("Adding troop training times to scheduling calculation:"));
                for (LocalDateTime trainingTime : troopTrainingTimes) {
                    LocalDateTime now = LocalDateTime.now();
                    Duration duration = Duration.between(now, trainingTime);

                    long totalMinutes = duration.toMinutes();
                    logInfo(routineLogUpgradeBuildingsLine("- Training time: " + totalMinutes + " minutes"));

                    int hours = (int) duration.toHours();
                    int minutes = (int) (duration.toMinutes() % 60);
                    int seconds = (int) (duration.getSeconds() % 60);
                    String timeString = String.format("%02d%02d%02d", hours, minutes, seconds);

                    UpgradeBuildingsRoutine.QueueSnapshot trainingState = new UpgradeBuildingsRoutine.QueueSnapshot(
                            UpgradeBuildingsRoutine.QueueMood.BUSY, timeString);
                    UpgradeBuildingsRoutine.QueueReadout trainingResult = new UpgradeBuildingsRoutine.QueueReadout(
                            -1, null, trainingState);

                    updatedResults.add(trainingResult);
                }
            }


            deferBasedOnBusyQueues(updatedResults);
            marchHelper.closeLeftMenu();
        } else {


            deferBasedOnBusyQueues(queueResults);
        }
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

private enum QueueMood {
        IDLE,

        BUSY,

        NOT_PURCHASED,

        IDLE_TEMP,

        UNKNOWN

    }

private record QueueSnapshot(UpgradeBuildingsRoutine.QueueMood status, String timeRemaining) {
    }

private record QueueReadout(int queueNumber, AreaData queueArea, UpgradeBuildingsRoutine.QueueSnapshot state) {

        @Override
        public @NotNull String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Queue ").append(queueNumber).append(": ");
            sb.append(state.status);
            if (state.timeRemaining != null) {
                sb.append(" (").append(state.timeRemaining).append(")");
            }
            return sb.toString();
        }
    }

private String routineLogUpgradeBuildingsLine(String note) {
        return "UpgradeBuildingsRoutine | " + note;
    }

private void refillResourcesIfNeededFlow() {
        ImageSearchResultData result;
        while ((result = templateSearchHelper.locatePattern(GAME_HOME_SHORTCUTS_OBTAIN,
                SearchConfigConstants.DEFAULT_SINGLE)).isFound()) {
            logInfo(routineLogUpgradeBuildingsLine("Refilling resources for the upgrade..."));
            tapRandomPoint(result.getPoint(), result.getPoint());
            sleepTask(500);


            tapPoint(new PointData(358, 1135));
            sleepTask(300);


            tapPoint(new PointData(511, 1056));
            sleepTask(1000);
        }
    }

private void handleSurvivorBuilding() {
        logInfo(routineLogUpgradeBuildingsLine("Handling Survivor Building"));


        int limit = 100;
        ImageSearchResultData survivorUpgrade;

        while (!(survivorUpgrade = templateSearchHelper.locatePattern(BUILDING_SURVIVOR_BUTTON_UPGRADE,
                SearchConfigConstants.SINGLE_WITH_2_RETRIES)).isFound()) {
            tapRandomPoint(new PointData(560, 640), new PointData(650, 690), 1, 200);
            limit--;
            if (limit <= 0) {
                break;
            }
        }


        tapRandomPoint(survivorUpgrade.getPoint(), survivorUpgrade.getPoint(), 1, 1000);


        refillResourcesIfNeededFlow();


        tapRandomPoint(new PointData(450, 1190), new PointData(600, 1230), 1, 1000);


        if (tapAllianceHelp()) {
            sleepTask(500);
            tapRandomPoint(new PointData(540, 1200), new PointData(700, 1250), 1, 1000);
        }
    }

private void deferBasedOnBusyQueues(List<QueueReadout> queueResults) {
        logInfo(routineLogUpgradeBuildingsLine("Zero IDLE queues available. Inspecting BUSY queues to reschedule..."));


        QueueReadout shortestBusyQueue = queueResults.stream()
                .filter(result -> result.state.status == QueueMood.BUSY && result.state.timeRemaining != null)
                .min((q1, q2) -> {
                    long time1 = decodeTimeToMinutes(q1.state.timeRemaining);
                    long time2 = decodeTimeToMinutes(q2.state.timeRemaining);
                    return Long.compare(time1, time2);
                })
                .orElse(null);

        if (shortestBusyQueue != null) {
            long minutesToWait = decodeTimeToMinutes(shortestBusyQueue.state.timeRemaining);
            LocalDateTime rescheduleTime;

            if (minutesToWait > 30) {

                long halfTime = minutesToWait / 2;
                rescheduleTime = LocalDateTime.now().plusMinutes(halfTime);
                logInfo(routineLogUpgradeBuildingsLine("Wait time exceeds 30 minutes (" + minutesToWait + " min). Planning next run for half time: " +
                        halfTime + " minutes from now"));
            } else if (minutesToWait < 5) {
                rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                logInfo(routineLogUpgradeBuildingsLine("Wait time is less than 5 minutes. Keeping normal schedule: " +
                        minutesToWait + " minutes from now"));
            } else {
                rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                logInfo(routineLogUpgradeBuildingsLine("Wait time is " + minutesToWait + " minutes. Using normal schedule"));
            }

            logInfo(routineLogUpgradeBuildingsLine("Shortest busy queue: Queue " + shortestBusyQueue.queueNumber +
                    " with " + shortestBusyQueue.state.timeRemaining + " remaining"));
            logInfo(routineLogUpgradeBuildingsLine("Planning next run task for: " + rescheduleTime));

            this.reschedule(rescheduleTime);
        } else {


            LocalDateTime rescheduleTime = LocalDateTime.now().plusHours(1);
            logWarning(routineLogUpgradeBuildingsLine("Zero BUSY queues with time information detected. Planning next run for 1 hour: " + rescheduleTime));
            this.reschedule(rescheduleTime);
        }
    }

private void logQueueSummaryFlow(List<UpgradeBuildingsRoutine.QueueReadout> queueResults) {
        logInfo(routineLogUpgradeBuildingsLine("=== Queue Analysis Summary ==="));
        for (UpgradeBuildingsRoutine.QueueReadout result : queueResults) {
            logInfo(routineLogUpgradeBuildingsLine(result.toString()));
        }
    }

private LocalDateTime handleTroopBuilding() {
        ImageSearchResultData train = templateSearchHelper.locatePattern(BUILDING_BUTTON_TRAIN,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (train.isFound()) {
            logInfo(routineLogUpgradeBuildingsLine("A troop training building was detected. Planning next run the task until the training is complete."));

            ImageSearchResultData speedupButton = templateSearchHelper.locatePattern(BUILDING_BUTTON_SPEED,
                    SearchConfigConstants.DEFAULT_SINGLE);

            if (speedupButton.isFound()) {
                tapRandomPoint(speedupButton.getPoint(), speedupButton.getPoint(), 1, 500);

                Duration trainingTime = durationHelper.attemptRecognition(
                        new PointData(292, 284),
                        new PointData(432, 314),
                        5,
                        300,
                        null,
                        GameTimeUtils::isAcceptedFormat,
                        GameTimeUtils::parseDuration);

                if (trainingTime == null) {
                    return null;
                }

                LocalDateTime completionTime = LocalDateTime.now().plus(trainingTime);

                logInfo(routineLogUpgradeBuildingsLine("Building will complete training at approximately: " + completionTime));

                return completionTime.minusSeconds(5);
            }
        }
        return null;
    }

private long decodeTimeToMinutes(String timeString) {
        if (timeString == null || timeString.isEmpty()) {
            return 0;
        }

        try {
            long totalMinutes = 0;
            String timePart = timeString.trim();


            if (timePart.toLowerCase().contains("d")) {
                String[] daysPart = timePart.toLowerCase().split("d");
                if (daysPart.length > 0) {
                    String daysStr = daysPart[0].replaceAll("[^0-9]", "");
                    if (!daysStr.isEmpty()) {
                        int days = Integer.parseInt(daysStr);
                        totalMinutes += (long) days * 24 * 60;

                    }
                }


                if (daysPart.length > 1) {
                    timePart = daysPart[1].trim();
                } else {
                    return totalMinutes;
                }
            }


            timePart = timePart.replaceAll("[^0-9:]", "");

            if (timePart.isEmpty()) {
                return totalMinutes;
            }


            if (timePart.contains(":")) {


                String[] timeParts = timePart.split(":");
                if (timeParts.length >= 2) {


                    if (!timeParts[0].isEmpty()) {
                        int hours = Integer.parseInt(timeParts[0]);
                        totalMinutes += hours * 60L;
                    }


                    if (!timeParts[1].isEmpty()) {
                        int minutes = Integer.parseInt(timeParts[1]);
                        totalMinutes += minutes;
                    }
                }
            } else {


                if (timePart.length() >= 4) {


                    String hoursStr = timePart.substring(0, 2);
                    int hours = Integer.parseInt(hoursStr);
                    totalMinutes += hours * 60L;


                    String minutesStr = timePart.substring(2, 4);
                    int minutes = Integer.parseInt(minutesStr);
                    totalMinutes += minutes;
                }
            }

            return totalMinutes;

        } catch (Exception e) {
            logError(routineLogUpgradeBuildingsLine("Error parsing time string '" + timeString + "': " + e.getMessage()));
            return 15;

        }
    }

private void handleCityBuilding() {
        logInfo(routineLogUpgradeBuildingsLine("Handling City Building"));


        ImageSearchResultData upgradeButton = templateSearchHelper.locatePattern(BUILDING_BUTTON_UPGRADE,
                SearchConfigConstants.RESILIENT);

        if (!upgradeButton.isFound()) {
            logWarning(routineLogUpgradeBuildingsLine("Upgrade button not detected"));
            this.setRecurring(false);
            return;
        }


        startBuildingAction("upgrade", upgradeButton.getPoint(), upgradeButton.getPoint());
    }

private void handleNewBuilding() {
        logInfo(routineLogUpgradeBuildingsLine("Handling New Building"));

        if (!isBuildButtonVisible()) {
            logWarning(routineLogUpgradeBuildingsLine("Build button not detected"));
            return;
        }

        startBuildingAction("build", BUILDING_ACTION_BUTTON_AREA_VALUE.topLeft(), BUILDING_ACTION_BUTTON_AREA_VALUE.bottomRight());
    }

private void startBuildingAction(String actionName, PointData buttonTopLeft, PointData buttonBottomRight) {
        logInfo(routineLogUpgradeBuildingsLine("Starting building " + actionName + "..."));

        tapRandomPoint(buttonTopLeft, buttonBottomRight);
        sleepTask(1000);


        refillResourcesIfNeededFlow();


        tapRandomPoint(BUILDING_CONFIRM_BUTTON_AREA_VALUE.topLeft(), BUILDING_CONFIRM_BUTTON_AREA_VALUE.bottomRight());


        tapAllianceHelp();
    }

private boolean isBuildButtonVisible() {
        try {
            emuManager.captureScreen(EMULATOR_NUMBER);
            String buttonText = emuManager.readText(
                    EMULATOR_NUMBER,
                    BUILDING_ACTION_BUTTON_AREA_VALUE.topLeft(),
                    BUILDING_ACTION_BUTTON_AREA_VALUE.bottomRight(),
                    WHITE_SETTINGS);
            String normalized = buttonText == null ? "" : buttonText.toLowerCase().replaceAll("[^a-z]", "");
            logDebug(routineLogUpgradeBuildingsLine("Build button OCR result: '" + buttonText + "'"));
            boolean detected = normalized.contains("build") || normalized.contains("bui");
            if (detected) {
                logInfo(routineLogUpgradeBuildingsLine("Build button detected via OCR: '" + buttonText + "'"));
            }
            return detected;
        } catch (Exception e) {
            logWarning(routineLogUpgradeBuildingsLine("Build button OCR failed: " + e.getMessage()));
            return false;
        }
    }

private void logQueueStateFlow(int queueIndex, UpgradeBuildingsRoutine.QueueSnapshot state) {
        switch (state.status) {
            case IDLE:
                logInfo(routineLogUpgradeBuildingsLine("Queue " + queueIndex + " is IDLE - available for use"));
                break;
            case BUSY:
                logInfo(routineLogUpgradeBuildingsLine("Queue " + queueIndex + " is BUSY - Time remaining: " + state.timeRemaining));
                break;
            case NOT_PURCHASED:
                logInfo(routineLogUpgradeBuildingsLine("Queue " + queueIndex + " is NOT PURCHASED - needs to be acquired"));
                break;
            case IDLE_TEMP:
                logInfo(routineLogUpgradeBuildingsLine("Queue " + queueIndex + " is IDLE_TEMP - detected by orange color"));
                break;
            case UNKNOWN:
                logWarning(routineLogUpgradeBuildingsLine("Queue " + queueIndex + " state is UNKNOWN - OCR did not complete to detect state"));
                break;
        }
    }

private UpgradeBuildingsRoutine.QueueSnapshot inspectQueueState(AreaData queueArea) {
        try {


            TesseractSettingsData[] settingsToTry = {
                    WHITE_SETTINGS,
                    WHITE_NUMBERS,
                    RED_SETTINGS,
                    ORANGE_SETTINGS,
            };

            for (TesseractSettingsData ocrPreset : settingsToTry) {
                String ocrText = emuManager.readText(
                        EMULATOR_NUMBER,
                        queueArea.topLeft(),
                        queueArea.bottomRight(),
                        ocrPreset).trim();

                logDebug(routineLogUpgradeBuildingsLine("OCR result with ocrPreset " + ocrPreset.getClass().getSimpleName() + ": '" + ocrText + "'"));


                if (ocrText.toLowerCase().contains("idle")) {

                    if (ocrPreset == ORANGE_SETTINGS) {
                        logDebug(routineLogUpgradeBuildingsLine("Orange 'idle' text detected - IDLE_TEMP"));
                        return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.IDLE_TEMP, null);
                    } else {
                        return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.IDLE, null);
                    }
                }


                if (ocrText.toLowerCase().contains("purchase") ||
                        ocrText.toLowerCase().contains("queue")) {
                    return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.NOT_PURCHASED, null);
                }


                if (ocrText.matches(".*(\\d+d\\s*)?\\d{6}.*")) {


                    String cleanedTime = ocrText.replaceAll("[^0-9d]", "").trim();
                    if (!cleanedTime.isEmpty()) {
                        return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.BUSY, cleanedTime);
                    }
                }
            }


            return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.UNKNOWN, null);

        } catch (Exception e) {
            logError(routineLogUpgradeBuildingsLine("Issue while OCR analysis: " + e.getMessage()));
            return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.UNKNOWN, null);
        }
    }

private List<UpgradeBuildingsRoutine.QueueReadout> inspectAllQueues() {
        List<UpgradeBuildingsRoutine.QueueReadout> results = new ArrayList<>();

        try {


            emuManager.captureScreen(EMULATOR_NUMBER);

            int queueIndex = 1;
            for (AreaData queueArea : queues) {
                logInfo(routineLogUpgradeBuildingsLine("Analyzing queue " + queueIndex));


                UpgradeBuildingsRoutine.QueueSnapshot state = inspectQueueState(queueArea);


                UpgradeBuildingsRoutine.QueueReadout result = new UpgradeBuildingsRoutine.QueueReadout(
                        queueIndex, queueArea, state);
                results.add(result);


                logQueueStateFlow(queueIndex, state);

                queueIndex++;
            }


            List<UpgradeBuildingsRoutine.QueueReadout> unknownResults = results.stream()
                    .filter(result -> result.state.status == UpgradeBuildingsRoutine.QueueMood.UNKNOWN)
                    .collect(Collectors.toList());

            if (!unknownResults.isEmpty()) {
                logInfo(routineLogUpgradeBuildingsLine("Detected " + unknownResults.size()
                        + " queue(s) with UNKNOWN status. Retrying with a new screenshot."));


                emuManager.captureScreen(EMULATOR_NUMBER);


                List<UpgradeBuildingsRoutine.QueueReadout> updatedResults = new ArrayList<>();


                for (UpgradeBuildingsRoutine.QueueReadout originalResult : results) {
                    if (originalResult.state.status == UpgradeBuildingsRoutine.QueueMood.UNKNOWN) {


                        logInfo(routineLogUpgradeBuildingsLine("Retrying analysis for queue " + originalResult.queueNumber));
                        UpgradeBuildingsRoutine.QueueSnapshot newState = inspectQueueState(originalResult.queueArea);


                        UpgradeBuildingsRoutine.QueueReadout newResult = new UpgradeBuildingsRoutine.QueueReadout(
                                originalResult.queueNumber, originalResult.queueArea, newState);


                        updatedResults.add(newResult);


                        logInfo(routineLogUpgradeBuildingsLine("Queue " + originalResult.queueNumber + " reanalyzed. New state: " + newState.status));
                        logQueueStateFlow(originalResult.queueNumber, newState);
                    } else {


                        updatedResults.add(originalResult);
                    }
                }


                results = updatedResults;
            }
        } catch (Exception e) {
            logError(routineLogUpgradeBuildingsLine("Error analyzing construction queues: " + e.getMessage()));
        }

        return results;
    }

private boolean tapAllianceHelp() {
        ImageSearchResultData help = templateSearchHelper.locatePatternMultiScale(
                GAME_HOME_SHORTCUTS_HELP_REQUEST4, SearchConfigConstants.HIGH_SENSITIVITY);

        if (help == null || !help.isFound()) {
            logWarning(routineLogUpgradeBuildingsLine("Alliance help button not detected"));
            return false;
        }

        tapRandomPoint(help.getPoint(), help.getPoint(), 1, 500);
        return true;
    }

private void reachCityView() {
        tapRandomPoint(LEFT_MENU.topLeft(), LEFT_MENU.bottomRight(), 1, 1000);
        tapRandomPoint(LEFT_MENU_CITY_TAB.topLeft(), LEFT_MENU_CITY_TAB.bottomRight(), 1,
                1000);
    }

private LocalDateTime handleQueue(UpgradeBuildingsRoutine.QueueReadout queueResult) {


        reachCityView();
        sleepTask(500);


        emuManager.touchArea(
                EMULATOR_NUMBER,
                queueResult.queueArea.topLeft(),
                queueResult.queueArea.bottomRight());
        sleepTask(500);


        ImageSearchResultData lowBuilding = templateSearchHelper.locatePattern(BUILDING_BUTTON_INFO,
                SearchConfigConstants.RESILIENT);

        if (lowBuilding.isFound()) {


            tapPoint(new PointData(lowBuilding.getPoint().getX() + 100, lowBuilding.getPoint().getY()));
            handleSurvivorBuilding();
            return null;
        } else {


            tapRandomPoint(new PointData(338, 799), new PointData(353, 807), 3, 100);
            ImageSearchResultData upgradeButton = templateSearchHelper.locatePattern(BUILDING_BUTTON_UPGRADE,
                    SearchConfigConstants.RESILIENT);

            if (upgradeButton.isFound()) {
                handleCityBuilding();
                return null;
            } else {

                if (isBuildButtonVisible()) {
                    handleNewBuilding();
                    return null;
                }

                return handleTroopBuilding();
            }
        }
    }
}
