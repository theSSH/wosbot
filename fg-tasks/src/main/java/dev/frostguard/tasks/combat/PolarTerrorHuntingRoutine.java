package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;

public class PolarTerrorHuntingRoutine extends DelayedTask {

private static final int DEFAULT_STAMINA_RESERVE = 130;

// Loaded from STAMINA_RESERVE_INT in hydrateConfiguration(): the reserve kept back
// for Intel/Rally (minStaminaLevel) and the level to regen back up to (refreshStaminaLevel).
private int refreshStaminaLevel = DEFAULT_STAMINA_RESERVE + 50;

private int minStaminaLevel = DEFAULT_STAMINA_RESERVE;

private final DailyTaskRepository iDailyTaskRepository = DailyTaskRepository.getRepository();

private final TaskManagementService taskManagementService = TaskManagementService.shared();

private static final int MAX_POLAR_LEVEL_LIMIT = 8;

private static final Map<Long, List<LocalDateTime>> activeDeployments = new ConcurrentHashMap<>();

private int polarTerrorLevel;

private boolean limitedHunting;

private int maxMarches;

public PolarTerrorHuntingRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

@Override
    protected void execute() {


        hydrateConfiguration();

        if (eventHelper.isBearRunning()) {
            LocalDateTime rescheduleTo = LocalDateTime.now().plusMinutes(30);
            logInfo(routineLogPolarTerrorHuntingLine("Bear Hunt is running, planning next run for " + rescheduleTo));
            reschedule(rescheduleTo);
            return;
        }
        logDebug(routineLogPolarTerrorHuntingLine("Bear Hunt is not running, continuing with Polar Terror Hunting Task"));

        if (profile.getConfig(ConfigurationKeyEnum.INTEL_BOOL, Boolean.class)
                && profile.getConfig(ConfigurationKeyEnum.POLAR_TERROR_ENABLED_BOOL, Boolean.class)
                && taskManagementService.lookupTaskState(profile.getId(), TpDailyTaskEnum.INTEL.getId()).isScheduled()) {


            DailyTask intel = iDailyTaskRepository.findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.INTEL);
            if (ChronoUnit.MINUTES.between(LocalDateTime.now(), intel.getScheduledAt()) < 5) {
                reschedule(LocalDateTime.now().plusMinutes(5));

                logWarning(routineLogPolarTerrorHuntingLine("Intel task is scheduled to run soon. Planning next run Polar Hunt to run 5 min after intel."));
                return;
            }
        }

        logInfo(routineLogPolarTerrorHuntingLine(String.format("Configuration: Level %d | %s Mode | Marches: %d",
                polarTerrorLevel,
                limitedHunting ? "Limited (10 hunts)" : "Unlimited",
                maxMarches)));


        if (!staminaHelper.checkStaminaAndMarchesOrReschedule(minStaminaLevel, refreshStaminaLevel, this::reschedule))
            return;


        if (limitedHunting && !polarsRemainingFlow(polarTerrorLevel)) {
            return;

        }

        int deployedCount = resolveActiveDeploymentsCount(profile.getId());
        int consecutiveFailures = 0;

        while (deployedCount < maxMarches) {


            if (!marchHelper.checkMarchesAvailable()) {
                logInfo(routineLogPolarTerrorHuntingLine("Zero marches available after " + deployedCount + " rallies. Waiting for marches to return."));
                reschedule(LocalDateTime.now().plusMinutes(1));
                return;
            }


            if (limitedHunting && !polarsRemainingFlow(polarTerrorLevel)) {
                return;

            }


            String flagConfigKey = "POLAR_TERROR_MARCH_" + (deployedCount + 1) + "_FLAG_STRING";
            ConfigurationKeyEnum enumKey = ConfigurationKeyEnum.valueOf(flagConfigKey);
            String flagString = profile.getConfig(enumKey, String.class);

            boolean useFlag = false;
            int currentFlagNumber = 0;

            if (flagString != null && !flagString.trim().equals("No Flag")) {
                try {
                    currentFlagNumber = Integer.parseInt(flagString.trim());
                    useFlag = true;
                } catch (NumberFormatException e) {
                    logWarning(routineLogPolarTerrorHuntingLine("Invalid flag number in config: " + flagString + " for march " + (deployedCount + 1)));
                    useFlag = false;
                }
            }

            logInfo(routineLogPolarTerrorHuntingLine("Launching rally " + (deployedCount + 1) + " of " + maxMarches +
                    (useFlag ? " with flag #" + currentFlagNumber : " (Equalize fallback)")));

            int result = launchSingleRallyFlow(polarTerrorLevel, useFlag, currentFlagNumber);

            if (result == -1) {


                logError(routineLogPolarTerrorHuntingLine("OCR error occurred during deployment " + (deployedCount + 1)));
                consecutiveFailures++;
                if (consecutiveFailures >= 3) {
                    logInfo(routineLogPolarTerrorHuntingLine("Too many consecutive OCR failures. Planning next run in 5 minutes."));
                    reschedule(LocalDateTime.now().plusMinutes(5));
                    return;
                }
                sleepTask(2000);
                continue;
            } else if (result == 0) {


                logInfo(routineLogPolarTerrorHuntingLine("Deployment " + (deployedCount + 1) + " did not complete. Planning next run in 5 minutes."));
                reschedule(LocalDateTime.now().plusMinutes(5));
                return;
            } else if (result == 3) {


                logInfo(routineLogPolarTerrorHuntingLine("Stamina too low after " + deployedCount + " rallies. Task rescheduled."));
                return;
            }


            deployedCount++;
            consecutiveFailures = 0;
            logInfo(routineLogPolarTerrorHuntingLine("Rally #" + deployedCount + " deployed finished cleanly. Current stamina: "
                    + staminaHelper.getCurrentStamina()));
            sleepTask(1500);
        }

        logInfo(routineLogPolarTerrorHuntingLine("Successfully deployed all " + maxMarches + " configured rallies. Planning next run main routine..."));


        reschedule(LocalDateTime.now().plusMinutes(15));

    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

@Override
    protected boolean consumesStamina() {
        return true;
    }

private String routineLogPolarTerrorHuntingLine(String note) {
        return "PolarTerrorHuntingRoutine | " + note;
    }

private static int resolveActiveDeploymentsCount(long profileId) {
        List<LocalDateTime> deployments = activeDeployments.get(profileId);
        if (deployments == null)
            return 0;


        deployments.removeIf(time -> LocalDateTime.now().isAfter(time));
        return deployments.size();
    }

private static void registerDeploymentFlow(long profileId, LocalDateTime returnTime) {
        activeDeployments.computeIfAbsent(profileId, k -> new CopyOnWriteArrayList<>())
                .add(returnTime);
    }

private int launchSingleRallyFlow(int polarLevel, boolean useFlag, int flagNumber) {
        navigationHelper.ensureCorrectScreenLocation(getRequiredStartLocation());


        logInfo(routineLogPolarTerrorHuntingLine("Moving to polars menu"));
        if (!openUpPolarsMenu(polarLevel)) {
            logError(routineLogPolarTerrorHuntingLine("Could not open polars menu."));
            return 0;
        }


        logInfo(routineLogPolarTerrorHuntingLine("Moving to rally menu"));
        if (!openUpRallyMenu()) {
            logError(routineLogPolarTerrorHuntingLine("Could not open rally menu."));
            return 0;
        }


        tapRandomPoint(new PointData(275, 821), new PointData(444, 856), 1, 400);
        sleepTask(500);


        if (useFlag) {
            marchHelper.selectFlag(flagNumber);
        } else {
            ImageSearchResultData equalizeBtn = templateSearchHelper.locatePattern(
                    TemplatesEnum.RALLY_EQUALIZE_BUTTON,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);
            if (equalizeBtn.isFound()) {
                tapPoint(equalizeBtn.getPoint());
                sleepTask(500);
            }
        }


        long travelTimeSeconds = staminaHelper.parseTravelTime();

        Integer spentStamina = staminaHelper.getSpentStamina();


        ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);

        if (!deploy.isFound()) {
            logDebug(routineLogPolarTerrorHuntingLine("Deploy button not detected. Planning next run to try again in 5 minutes."));
            return 0;
        }

        tapPoint(deploy.getPoint());
        sleepTask(2000);

        deploy = templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (deploy.isFound()) {


            logInfo(routineLogPolarTerrorHuntingLine("Deploy button still detected after trying to deploy march. Planning next run to try again in 5 minutes."));
            return 0;
        }

        logInfo(routineLogPolarTerrorHuntingLine("March deployed finished cleanly."));


        staminaHelper.subtractStamina(spentStamina, true);


        if (travelTimeSeconds > 0) {
            long returnTimeSeconds = travelTimeSeconds * 2 + 2;
            LocalDateTime returnTime = LocalDateTime.now().plusSeconds(returnTimeSeconds);
            registerDeploymentFlow(profile.getId(), returnTime);
            logInfo(routineLogPolarTerrorHuntingLine("March scheduled to return at " + dev.frostguard.vision.convert.GameTimeUtils.formatCountdown(returnTime)));
        } else {


            logWarning(routineLogPolarTerrorHuntingLine("Could not parse travel time via OCR. Holding slot for 10 minutes."));
            registerDeploymentFlow(profile.getId(), LocalDateTime.now().plusMinutes(10));
        }


        if (staminaHelper.getCurrentStamina() <= minStaminaLevel) {
            logInfo(routineLogPolarTerrorHuntingLine("Stamina is at or below minimum. Stopping deployment and planning next run."));
            reschedule(
                    LocalDateTime.now().plusMinutes(staminaHelper
                            .staminaRegenerationTime(staminaHelper.getCurrentStamina(), refreshStaminaLevel)));
            return 3;
        }
        return 1;
    }

private void hydrateConfiguration() {
        this.polarTerrorLevel = profile.getConfig(ConfigurationKeyEnum.POLAR_TERROR_LEVEL_INT, Integer.class);
        this.limitedHunting = profile.getConfig(ConfigurationKeyEnum.POLAR_TERROR_MODE_STRING, String.class)
                .equals("Limited (10)");
        this.maxMarches = profile.getConfig(ConfigurationKeyEnum.POLAR_TERROR_MARCHES_INT, Integer.class);

        Integer configReserve = profile.getConfig(ConfigurationKeyEnum.STAMINA_RESERVE_INT, Integer.class);
        this.minStaminaLevel = (configReserve != null) ? configReserve : DEFAULT_STAMINA_RESERVE;
        this.refreshStaminaLevel = this.minStaminaLevel + 50;


        if (this.maxMarches < 1)
            this.maxMarches = 1;
        if (this.maxMarches > 6)
            this.maxMarches = 6;

        logDebug(routineLogPolarTerrorHuntingLine("Configuration loaded: polarLevel=" + polarTerrorLevel + ", limitedHunting=" + limitedHunting +
                ", maxMarches=" + maxMarches));
    }

private boolean polarsRemainingFlow(int polarLevel) {
        if (!openUpPolarsMenu(polarLevel)) {
            return false;
        }


        ImageSearchResultData magnifyingGlass = templateSearchHelper.locatePattern(
                TemplatesEnum.POLAR_TERROR_TAB_MAGNIFYING_GLASS_ICON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        logDebug(routineLogPolarTerrorHuntingLine("Scanning for magnifying glass icon"));
        sleepTask(500);

        if (!magnifyingGlass.isFound()) {
            return false;
        }


        tapPoint(magnifyingGlass.getPoint());
        sleepTask(2000);

        ImageSearchResultData specialRewardsCompleted = templateSearchHelper.locatePattern(
                TemplatesEnum.POLAR_TERROR_TAB_SPECIAL_REWARDS,
                SearchConfigConstants.QUICK_SEARCH);
        for (int i = 0; i < 5 && !specialRewardsCompleted.isFound(); i++) {
            specialRewardsCompleted = templateSearchHelper.locatePattern(
                    TemplatesEnum.POLAR_TERROR_TAB_SPECIAL_REWARDS,
                    SearchConfigConstants.QUICK_SEARCH);
            if (specialRewardsCompleted.isFound()) {


                logWarning(routineLogPolarTerrorHuntingLine("Zero special rewards left, meaning there's no hunts left for today. Planning next run task for reset"));


                reschedule(GameTimeUtils.dailyResetTime().plusMinutes(30));
                return false;
            }
            swipe(new PointData(363, 1088), new PointData(363, 1030));
            sleepTask(300);
        }


        pressBack();
        sleepTask(500);
        return true;
    }

private boolean openUpPolarsMenu(int polarLevel) {


        tapRandomPoint(new PointData(25, 850), new PointData(67, 898));
        sleepTask(2000);


        swipe(new PointData(40, 913), new PointData(678, 913));
        sleepTask(500);


        ImageSearchResultData polarTerror = templateSearchHelper.locatePattern(
                TemplatesEnum.POLAR_TERROR_SEARCH_ICON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        logDebug(routineLogPolarTerrorHuntingLine("Scanning for Polar Terror icon"));
        for (int i = 0; i < 3 && !polarTerror.isFound(); i++) {
            swipe(new PointData(40, 913), new PointData(678, 913));
            sleepTask(500);
            polarTerror = templateSearchHelper.locatePattern(
                    TemplatesEnum.POLAR_TERROR_SEARCH_ICON,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);
        }

        if (!polarTerror.isFound()) {
            logWarning(routineLogPolarTerrorHuntingLine("Could not find the polar terrors."));
            return false;
        }


        PointData[] levelPoints = {
                new PointData(129, 1052),

                new PointData(200, 1052),

                new PointData(280, 1052),

                new PointData(357, 1052),

                new PointData(432, 1052),

        };


        tapPoint(polarTerror.getPoint());
        sleepTask(100);
        if (polarLevel != -1) {
            logInfo(routineLogPolarTerrorHuntingLine(String.format("Adjusting Polar Terror level to %d", polarLevel)));
            if (polarLevel < 4 || polarLevel > levelPoints.length + 3) {
                logError(routineLogPolarTerrorHuntingLine(String.format("Invalid Polar Terror level configured: %d. Must be between 4 and %d.",
                        polarLevel, MAX_POLAR_LEVEL_LIMIT)));
                return false;
            }
            tapRandomPoint(levelPoints[polarLevel - 4], levelPoints[polarLevel - 4], 3, 100);
        }


        logDebug(routineLogPolarTerrorHuntingLine("Pressing on search button..."));
        tapRandomPoint(new PointData(301, 1200), new PointData(412, 1229));
        sleepTask(1500);
        return true;
    }

private boolean openUpRallyMenu() {


        ImageSearchResultData rallyButton = templateSearchHelper.locatePattern(
                TemplatesEnum.RALLY_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        sleepTask(500);

        if (!rallyButton.isFound()) {
            logDebug(routineLogPolarTerrorHuntingLine("Rally button not detected."));
            sleepTask(500);
            return false;
        }

        tapPoint(rallyButton.getPoint());
        sleepTask(1000);
        return true;
    }
}
