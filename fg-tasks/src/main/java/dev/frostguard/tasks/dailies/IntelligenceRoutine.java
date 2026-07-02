package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import java.awt.Color;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.tess4j.TesseractException;

public class IntelligenceRoutine extends DelayedTask {

private static final int MIN_STAMINA_REQUIRED_FLOOR = 30;

private static final int SURVIVOR_STAMINA_COST_VALUE = 12;

private static final int JOURNEY_STAMINA_COST_VALUE = 10;

private static final int SMART_PROCESSING_MIN_IDLE_MARCHES_FOR_INTEL = 2;

private static final PointData MARCH_RECALL_CONFIRM_TOP_LEFT = new PointData(446, 780);

private static final PointData MARCH_RECALL_CONFIRM_BOTTOM_RIGHT = new PointData(578, 800);

private static final MarchQueueRegion[] MARCH_QUEUE_REGIONS = {
		new MarchQueueRegion(new PointData(10, 342), new PointData(435, 407), new PointData(152, 378)),
		new MarchQueueRegion(new PointData(10, 415), new PointData(435, 480), new PointData(152, 451)),
		new MarchQueueRegion(new PointData(10, 488), new PointData(435, 553), new PointData(152, 524)),
		new MarchQueueRegion(new PointData(10, 561), new PointData(435, 626), new PointData(152, 597)),
		new MarchQueueRegion(new PointData(10, 634), new PointData(435, 699), new PointData(152, 670)),
		new MarchQueueRegion(new PointData(10, 707), new PointData(435, 772), new PointData(152, 743)),
};

private static final int MARCH_QUEUE_TIME_TEXT_WIDTH = 140;

private static final int MARCH_QUEUE_TIME_TEXT_HEIGHT = 19;

private final DailyTaskRepository iDailyTaskRepository = DailyTaskRepository.getRepository();

private boolean marchQueueLimitReached;

private boolean autoJoinDisabledForIntel;

private boolean recallGatherTroopsFlow;

private boolean fcEra;

private boolean useSmartProcessing;

private boolean useFlag;

private Integer flagNumber;

private boolean beastsEnabled;

private boolean fireBeastsEnabled;

private boolean survivorCampsEnabled;

private boolean explorationsEnabled;

private boolean isAutoJoinTaskEnabled;

private boolean processingTask;

// Changed by pernerch | Date: 2026-07-02 | Why: ensure gather and autojoin can be resumed after Intel priority handling.
private boolean shouldRequeueGatherAfterIntel;

// Changed by pernerch | Date: 2026-07-02 | Why: restore autojoin after Intel processing so helping rallies continues.
private boolean shouldRequeueAutoJoinAfterIntel;

// Changed by pernerch | Date: 2026-07-02 | Why: track beast march dispatch to keep intel rescheduling accurate.
private boolean beastMarchSent;

private TaskStateData autoJoinTask;

private ResilientOcrExecutor<LocalDateTime> textHelper;

private SearchConfig searchConfigMultiple = SearchConfig.builder()
			.withMaxAttempts(3)
			.withDelay(300L)
			.withThreshold(90)
			.withMaxResults(10)
			.build();

public IntelligenceRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
		super(profile, tpTask);
	}

@Override
	protected void execute() {


		hydrateConfiguration();


		processingTask = true;
		beastMarchSent = false;
		shouldRequeueGatherAfterIntel = false;
		shouldRequeueAutoJoinAfterIntel = false;

		autoJoinTask = TaskManagementService.shared().lookupTaskState(profile.getId(),
				TpDailyTaskEnum.ALLIANCE_AUTOJOIN.getId());
		isAutoJoinTaskEnabled = (autoJoinTask != null) ? true : false;

		if (!autoJoinDisabledForIntel && isAutoJoinTaskEnabled && autoJoinTask.isScheduled()) {
			logInfo(routineLogIntelligenceLine("Auto-join is enabled and scheduled, proceeding to disable it."));
			autoJoinDisabledForIntel = allianceHelper.disableAutoJoin();
			if (!autoJoinDisabledForIntel)
				logDebug(routineLogIntelligenceLine("Could not disable auto-join, proceeding anyway."));
			else {
				shouldRequeueAutoJoinAfterIntel = true;
			}
		}

		try {


		// Changed by pernerch | Date: 2026-07-02 | Why: check mission availability before
		// recalling gather marches, so Intel does not disrupt gathering when nothing is actionable.
		boolean intelMissionsDetected = hasAnyIntelMissionAvailableFlow();
		if (!intelMissionsDetected) {
			logInfo(routineLogIntelligenceLine("No intel missions detected. Skipping Intel run for now."));
			tryRescheduleFromCooldownFlow();
			processingTask = false;
			return;
		}

		// Changed by pernerch | Date: 2026-07-02 | Why: return to the world screen so gather marches can be recalled from the correct UI context.
		navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);

		// Changed by pernerch | Date: 2026-07-02 | Why: Intel must preempt gather for full-march execution when smart processing is disabled.
		if (!useSmartProcessing || recallGatherTroopsFlow) {
			logInfo(routineLogIntelligenceLine("Intel gather-priority mode active (smart=" + useSmartProcessing
					+ ", recall=" + recallGatherTroopsFlow + "). Recalling all gather troops..."));
			recallGatherTroopsFlow();
			shouldRequeueGatherAfterIntel = true;
			logInfo(routineLogIntelligenceLine("All gather troops recalled. Proceeding with intel processing."));
		} else {
			// Changed by pernerch | Date: 2026-07-02 | Why: in smart processing mode, free Intel marches by recalling long-running duplicate gather marches first.
			int recalledDuplicateMarches = recallDuplicateGatherMarchesForSmartProcessingFlow();
			if (recalledDuplicateMarches > 0) {
				shouldRequeueGatherAfterIntel = true;
				logInfo(routineLogIntelligenceLine("Smart processing recalled " + recalledDuplicateMarches
						+ " duplicate gather march(es) to free Intel capacity."));
			}
		}

		while (processingTask) {
			boolean anyIntelProcessed = false;
			boolean nonBeastIntelProcessed = false;
			beastMarchSent = false;


			navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);


			MarchesAvailable marchesAvailable = inspectMarchAvailability();
			marchQueueLimitReached = !marchesAvailable.available();


			redeemCompletedMissions();


			if (!hasEnoughStaminaFlow()) {
				processingTask = false;
				return;

			}


			if (beastsEnabled && shouldProcessBeastsFlow()) {
				if (handleBeastIntel()) {
					anyIntelProcessed = true;
				}
			}


			if (survivorCampsEnabled) {
				intelScreenHelper.ensureOnIntelScreen();
				logInfo(routineLogIntelligenceLine("Scanning for survivor camps using grayscale matching."));
				TemplatesEnum survivorTemplate = fcEra ? TemplatesEnum.INTEL_SURVIVOR_GRAYSCALE_FC
						: TemplatesEnum.INTEL_SURVIVOR_GRAYSCALE;
				if (seekAndProcessGrayscale(survivorTemplate, this::handleSurvivor)) {
					anyIntelProcessed = true;
					nonBeastIntelProcessed = true;
				}
			}


			if (explorationsEnabled) {
				intelScreenHelper.ensureOnIntelScreen();
				logInfo(routineLogIntelligenceLine("Scanning for explorations using grayscale matching."));
				TemplatesEnum journeyTemplate = fcEra ? TemplatesEnum.INTEL_JOURNEY_GRAYSCALE_FC
						: TemplatesEnum.INTEL_JOURNEY_GRAYSCALE;
				if (seekAndProcessGrayscale(journeyTemplate, this::handleJourney)) {
					anyIntelProcessed = true;
					nonBeastIntelProcessed = true;
				}
			}


			manageRescheduling(anyIntelProcessed, nonBeastIntelProcessed, marchesAvailable);
		}
		} finally {
			finalizePostIntelTaskFlow();
		}

	}

private boolean hasAnyIntelMissionAvailableFlow() {
		// Changed by pernerch | Date: 2026-07-02 | Why: lightweight pre-check to avoid unnecessary
		// gather recalls when Intel has no visible missions to process.
		intelScreenHelper.ensureOnIntelScreen();

		if (fireBeastsEnabled && templateSearchHelper
				.locatePatternMono(TemplatesEnum.INTEL_FIRE_BEAST, SearchConfigConstants.DEFAULT_SINGLE)
				.isFound()) {
			return true;
		}

		if (beastsEnabled) {
			TemplatesEnum[] beastTemplates = fcEra
					? new TemplatesEnum[] { TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC, TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC1 }
					: new TemplatesEnum[] { TemplatesEnum.INTEL_BEAST_GRAYSCALE };

			for (TemplatesEnum template : beastTemplates) {
				if (templateSearchHelper.locatePatternMono(template, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
					return true;
				}
			}
		}

		if (survivorCampsEnabled) {
			TemplatesEnum survivorTemplate = fcEra ? TemplatesEnum.INTEL_SURVIVOR_GRAYSCALE_FC
					: TemplatesEnum.INTEL_SURVIVOR_GRAYSCALE;
			if (templateSearchHelper.locatePatternMono(survivorTemplate, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
				return true;
			}
		}

		if (explorationsEnabled) {
			TemplatesEnum journeyTemplate = fcEra ? TemplatesEnum.INTEL_JOURNEY_GRAYSCALE_FC
					: TemplatesEnum.INTEL_JOURNEY_GRAYSCALE;
			if (templateSearchHelper.locatePatternMono(journeyTemplate, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
				return true;
			}
		}

		return false;
	}

@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.WORLD;
	}

@Override
	protected boolean consumesStamina() {
		return true;
	}

private enum GatherTypeShape {
		MEAT("meat", TemplatesEnum.GAME_HOME_SHORTCUTS_MEAT),
		WOOD("wood", TemplatesEnum.GAME_HOME_SHORTCUTS_WOOD),
		COAL("coal", TemplatesEnum.GAME_HOME_SHORTCUTS_COAL),
		IRON("iron", TemplatesEnum.GAME_HOME_SHORTCUTS_IRON);

		final String name;
		final TemplatesEnum template;

		GatherTypeShape(String name, TemplatesEnum enumTemplate) {
			this.name = name;
			this.template = enumTemplate;
		}

		public String getName() {
			return name;
		}

		public TemplatesEnum getTemplate() {
			return template;
		}
	}

public record MarchesAvailable(boolean available, LocalDateTime rescheduleTo) {
	}

private void tryRescheduleFromCooldownFlow() {
		logInfo(routineLogIntelligenceLine("Zero intel items detected. Attempting to read the cooldown timer."));

		LocalDateTime cooldown = textHelper.attemptRecognition(
				new PointData(378, 103),
				new PointData(508, 146),
				3,
				200L,
				TesseractSettingsData.assembler()
						.charWhitelist("0123456789")
						.stripBackground(true)
						.setTextColor(new Color(255, 255, 255))
						.build(),
				GameTimeUtils::isAcceptedFormat,
				text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));

		if (cooldown == null) {
			logWarning(routineLogIntelligenceLine("Could not read cooldown timer via OCR. Planning next run in 10 minutes."));
			reschedule(LocalDateTime.now().plusMinutes(10));
			pressBack();
			return;
		}

		reschedule(cooldown);
		pressBack();

		logInfo(routineLogIntelligenceLine("Zero new intel detected. Planning next run task to run at: " + cooldown.format(DATETIME_FORMATTER)));
	}

private String routineLogIntelligenceLine(String note) {
        return "IntelligenceRoutine | " + note;
    }

private MarchesAvailable inspectMarchAvailability() {
		if (useSmartProcessing) {
			return resolveMarchesAvailable();
		} else {
			boolean available = marchHelper.checkMarchesAvailable();
			return new MarchesAvailable(available, LocalDateTime.now());
		}
	}

private boolean shouldProcessBeastsFlow() {


		if (useFlag && beastMarchSent) {
			logInfo(routineLogIntelligenceLine("Beast march already sent (flag mode), skipping beast search."));
			return false;
		}

		return true;
	}

private boolean seekAndProcessGrayscale(TemplatesEnum template, Consumer<ImageSearchResultData> processMethod) {
		logInfo(routineLogIntelligenceLine("Scanning for grayscale template '" + template + "'"));
		ImageSearchResultData result = templateSearchHelper.locatePatternMono(template, SearchConfigConstants.SINGLE_WITH_RETRIES);

		if (result.isFound()) {
			logInfo(routineLogIntelligenceLine("Grayscale template detected: " + template));
			processMethod.accept(result);
			return true;
		}
		logWarning(routineLogIntelligenceLine("Grayscale template not detected: " + template));
		return false;
	}

private MarchesAvailable resolveMarchesAvailable() {


		marchHelper.openLeftMenuCitySection(false);

		TesseractSettingsData ocrPreset = TesseractSettingsData.assembler()
				.charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
				.recognitionEngine(TesseractSettingsData.RecognitionEngine.LSTM_ONLY)
				.build();


		try {
			for (int i = 0; i < 5; i++) {
				String ocrSearchResult = emuManager.readText(EMULATOR_NUMBER,
						new PointData(10, 342),
						new PointData(435, 772),
						ocrPreset);
				Pattern idleMarchesPattern = Pattern.compile("idle");
				Matcher m = idleMarchesPattern.matcher(ocrSearchResult.toLowerCase());
				if (m.find()) {
					logInfo(routineLogIntelligenceLine("Idle marches detected via OCR"));
					return new MarchesAvailable(true, null);
				} else {
					logDebug(routineLogIntelligenceLine("Zero idle marches detected via OCR (Attempt " + (i + 1) + "/5)."));
				}
			}
		} catch (IOException | TesseractException e) {
			logDebug(routineLogIntelligenceLine("OCR attempt did not complete: " + e.getMessage()));
		}

		logInfo(routineLogIntelligenceLine("Zero idle marches detected via OCR. Analyzing gather march queues..."));


		int totalMarchesAvailable = profile.getConfig(ConfigurationKeyEnum.GATHER_ACTIVE_MARCH_QUEUE_INT,
				Integer.class);
		int activeMarchQueues = 0;
		LocalDateTime earliestAvailableMarch = LocalDateTime.now().plusHours(14);


		for (GatherTypeShape gatherType : GatherTypeShape.values()) {
			ImageSearchResultData resource = templateSearchHelper.locatePattern(gatherType.getTemplate(), SearchConfigConstants.SINGLE_WITH_RETRIES);
			if (!resource.isFound()) {
				logDebug(routineLogIntelligenceLine("March queue for " + gatherType.getName() + " is not active. (Used: " +
						activeMarchQueues + "/" + totalMarchesAvailable + ")"));
				continue;
			}


			activeMarchQueues++;
			logInfo(routineLogIntelligenceLine("March queue for " + gatherType.getName() + " detected. (Used: " +
					activeMarchQueues + "/" + totalMarchesAvailable + ")"));


			DailyTask gatherTask = iDailyTaskRepository
					.findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.GATHER_RESOURCES);

			if (gatherTask != null && gatherTask.getScheduledAt() != null) {
				LocalDateTime nextSchedule = gatherTask.getScheduledAt();

				if (nextSchedule.isBefore(earliestAvailableMarch)) {
					earliestAvailableMarch = nextSchedule;
					logInfo(routineLogIntelligenceLine("Updated earliest available march: " + earliestAvailableMarch));
				}
			}
		}

		if (activeMarchQueues >= totalMarchesAvailable) {
			logInfo(routineLogIntelligenceLine("All march queues used. Earliest available march: " + earliestAvailableMarch));
			return new MarchesAvailable(false, earliestAvailableMarch);
		}


		logInfo(routineLogIntelligenceLine("Not all march queues used (" + activeMarchQueues + "/" + totalMarchesAvailable +
				"), but no idle marches. Suspected auto-rally marches. Planning next run in 5 minutes."));
		return new MarchesAvailable(false, LocalDateTime.now().plusMinutes(5));
	}

private void redeemCompletedMissions() {
		intelScreenHelper.ensureOnIntelScreen();
		logInfo(routineLogIntelligenceLine("Scanning for completed missions to claim."));

		for (int i = 0; i < 2; i++) {
			logDebug(routineLogIntelligenceLine("Scanning for completed missions. Attempt " + (i + 1) + "."));
			List<ImageSearchResultData> completed = templateSearchHelper.locateAllPatterns(
					TemplatesEnum.INTEL_COMPLETED,
					searchConfigMultiple);

			if (completed.isEmpty()) {
				logInfo(routineLogIntelligenceLine("Zero completed missions detected on attempt " + (i + 1) + "."));
				continue;
			}

			logInfo(routineLogIntelligenceLine("Detected " + completed.size() + " completed missions. Collecting them now."));

			for (ImageSearchResultData completedMission : completed) {
				tapPoint(completedMission.getPoint());
				sleepTask(500);
				tapRandomPoint(new PointData(700, 1270), new PointData(710, 1280), 3, 100);
				sleepTask(500);
			}
		}
	}

private void requeueGatherTasksFlow() {
		logInfo(routineLogIntelligenceLine("Re-queueing gather tasks after Intel completion..."));


		TaskQueue queue = dev.frostguard.engine.service.ScheduleService.obtain().getCoordinator().getQueue(profile.getId());
		if (queue == null) {
			logError(routineLogIntelligenceLine("Could not access task queue for profile " + profile.getName()));
			return;
		}


		if (profile.getConfig(ConfigurationKeyEnum.GATHER_TASK_BOOL, Boolean.class)) {
			queue.runNow(TpDailyTaskEnum.GATHER_RESOURCES, true);
			logInfo(routineLogIntelligenceLine("Re-queued Gather Resources task"));
		}

		sleepTask(500);
	}

private void requeueAutoJoinTaskFlow() {
		if (!isAutoJoinTaskEnabled || autoJoinTask == null) {
			return;
		}

		Boolean autoJoinEnabled = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL, Boolean.class);
		if (!Boolean.TRUE.equals(autoJoinEnabled)) {
			return;
		}

		TaskQueue queue = dev.frostguard.engine.service.ScheduleService.obtain().getCoordinator().getQueue(profile.getId());
		if (queue == null) {
			logError(routineLogIntelligenceLine("Could not access task queue to re-queue autojoin for profile " + profile.getName()));
			return;
		}

		queue.runNow(TpDailyTaskEnum.ALLIANCE_AUTOJOIN, true);
		logInfo(routineLogIntelligenceLine("Re-queued Alliance Autojoin task after Intel completion."));
	}

private void finalizePostIntelTaskFlow() {
		if (shouldRequeueGatherAfterIntel) {
			requeueGatherTasksFlow();
		}

		if (shouldRequeueAutoJoinAfterIntel) {
			requeueAutoJoinTaskFlow();
		}

		autoJoinDisabledForIntel = false;
		shouldRequeueGatherAfterIntel = false;
		shouldRequeueAutoJoinAfterIntel = false;
	}

private void recallGatherTroopsFlow() {
		int maxRetries = 120;

		int attempt = 0;

		logInfo(routineLogIntelligenceLine("Recalling all gather troops to the city..."));
		SearchConfig marchStatusSearchConfig = SearchConfig.builder()
				.withThreshold(90)
				.withMaxAttempts(5)
				.withDelay(200L)
				.build();

		while (attempt < maxRetries) {
			attempt++;
			ImageSearchResultData returningArrow = locatePatternWithMonoFallback(
					TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
					marchStatusSearchConfig);
			ImageSearchResultData marchView = locatePatternWithMonoFallback(
					TemplatesEnum.MARCHES_AREA_VIEW_BUTTON,
					marchStatusSearchConfig);
			ImageSearchResultData marchSpeedup = locatePatternWithMonoFallback(
					TemplatesEnum.MARCHES_AREA_SPEEDUP_BUTTON,
					marchStatusSearchConfig);

			boolean foundReturning = returningArrow != null && returningArrow.isFound();
			boolean foundView = marchView != null && marchView.isFound();
			boolean foundSpeedup = marchSpeedup != null && marchSpeedup.isFound();

			logDebug(routineLogIntelligenceLine(String.format(
					"recallGatherTroopsFlow status => returning:%b view:%b speedup:%b (attempt %d)",
					foundReturning, foundView, foundSpeedup, attempt)));

			if (!foundReturning && !foundView && !foundSpeedup) {
				int idleMarches = countIdleMarchesFlow();
				if (idleMarches >= 6) {
					logInfo(routineLogIntelligenceLine("Zero march indicators detected and all marches are idle. Recall is complete."));
					return;
				}

				logWarning(routineLogIntelligenceLine(
						"Zero recall/view/speedup indicators detected, but only " + idleMarches +
						"/6 marches are idle. Retrying recall to avoid false success."));
				sleepTask(600);
				continue;
			}

			if (foundReturning) {
				logInfo(routineLogIntelligenceLine("Returning arrow detected - attempting to tap recall button"));
				tapRandomPoint(returningArrow.getPoint(), returningArrow.getPoint(), 1, 300);
				tapRandomPoint(new PointData(446, 780), new PointData(578, 800), 1, 200);
			}

			if (foundView || foundSpeedup) {
				logInfo(routineLogIntelligenceLine("Troops are still marching - waiting for them to return"));
				sleepTask(1000);
			}

			sleepTask(200);
		}

		logError(routineLogIntelligenceLine("recallGatherTroopsFlow exceeded max attempts (" + maxRetries + "), exiting to avoid deadlock"));
	}

	// Changed by pernerch | Date: 2026-07-02 | Why: keep detection and recall click in the same
	// tab-open cycle so a found recall button can be acted on immediately without UI drift.
	private TabRecallResult inspectAndRecallForTabFlow(boolean cityTab, SearchConfig searchConfig) {
		int tapped = 0;
		marchHelper.openLeftMenuCitySection(cityTab);
		sleepTask(350);
		try {
			ImageSearchResultData returningArrow = locatePatternWithMonoFallback(
					TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
					searchConfig);
			ImageSearchResultData marchView = locatePatternWithMonoFallback(
					TemplatesEnum.MARCHES_AREA_VIEW_BUTTON,
					searchConfig);
			ImageSearchResultData marchSpeedup = locatePatternWithMonoFallback(
					TemplatesEnum.MARCHES_AREA_SPEEDUP_BUTTON,
					searchConfig);

			// Changed by pernerch | Date: 2026-07-02 | Why: gather recall must act on the full set
			// of visible recall buttons in the opened tab instead of repeatedly probing one button.
			if (returningArrow != null && returningArrow.isFound()) {
				List<ImageSearchResultData> recallButtons = locateAllPatternsWithMonoFallback(
						TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
						SearchConfig.builder()
								.withThreshold(searchConfig.getThreshold())
								.withMaxAttempts(searchConfig.getMaxAttempts())
								.withDelay(searchConfig.getDelayBetweenAttempts())
								.withMaxResults(6)
								.build());

				Map<String, ImageSearchResultData> uniqueRecallButtons = new LinkedHashMap<>();
				for (ImageSearchResultData recallButton : recallButtons) {
					if (recallButton != null && recallButton.isFound()) {
						String key = recallButton.getPoint().getX() + ":" + recallButton.getPoint().getY();
						uniqueRecallButtons.putIfAbsent(key, recallButton);
					}
				}

				for (ImageSearchResultData recallButton : uniqueRecallButtons.values()) {
					tapRandomPoint(recallButton.getPoint(), recallButton.getPoint(), 1, 300);
					tapRandomPoint(new PointData(446, 780), new PointData(578, 800), 1, 200);
					sleepTask(500);
					tapped++;
				}
			} else if (marchView != null && marchView.isFound() || marchSpeedup != null && marchSpeedup.isFound()) {
				List<GatherMarchCandidate> visibleRows = collectVisibleGatherRowsForRecallFlow(searchConfig);
				for (GatherMarchCandidate row : visibleRows) {
					if (tapGatherRowThenRecallFlow(row, searchConfig)) {
						tapped++;
					}
				}
			}

			return new TabRecallResult(new MarchStatusShape(
					returningArrow != null && returningArrow.isFound(),
					marchView != null && marchView.isFound(),
					marchSpeedup != null && marchSpeedup.isFound()), tapped);
		} finally {
			marchHelper.closeLeftMenu();
		}
	}

private record MarchStatusShape(boolean hasRecallButton, boolean hasViewButton, boolean hasSpeedupButton) {
	}

private record TabRecallResult(MarchStatusShape status, int tappedButtons) {
	}

private boolean tapGatherRowThenRecallFlow(GatherMarchCandidate candidate, SearchConfig searchConfig) {
		tapRandomPoint(candidate.rowPoint(), candidate.rowPoint(), 1, 300);
		sleepTask(350);

		ImageSearchResultData recallButton = locatePatternWithMonoFallback(
				TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
				SearchConfig.builder()
						.withThreshold(searchConfig.getThreshold())
						.withMaxAttempts(searchConfig.getMaxAttempts())
						.withDelay(searchConfig.getDelayBetweenAttempts())
						.build());

		if (recallButton == null || !recallButton.isFound()) {
			return false;
		}

		tapRandomPoint(recallButton.getPoint(), recallButton.getPoint(), 1, 300);
		tapRandomPoint(MARCH_RECALL_CONFIRM_TOP_LEFT, MARCH_RECALL_CONFIRM_BOTTOM_RIGHT, 1, 200);
		sleepTask(500);
		return true;
	}

private List<GatherMarchCandidate> collectVisibleGatherRowsForRecallFlow(SearchConfig searchConfig) {
		List<GatherMarchCandidate> rows = new ArrayList<>();
		Map<String, GatherMarchCandidate> uniqueRows = new LinkedHashMap<>();

		for (GatherTypeShape gatherType : GatherTypeShape.values()) {
			List<ImageSearchResultData> detections = locateAllPatternsWithMonoFallback(
					gatherType.getTemplate(),
					SearchConfig.builder()
							.withArea(new AreaData(MARCH_QUEUE_REGIONS[0].topLeft(), MARCH_QUEUE_REGIONS[MARCH_QUEUE_REGIONS.length - 1].bottomRight()))
							.withThreshold(searchConfig.getThreshold())
							.withMaxAttempts(searchConfig.getMaxAttempts())
							.withDelay(searchConfig.getDelayBetweenAttempts())
							.withMaxResults(MARCH_QUEUE_REGIONS.length)
							.build());

			for (ImageSearchResultData detection : detections) {
				if (detection == null || !detection.isFound()) {
					continue;
				}

				int queueIndex = findQueueIndexByPointFlow(detection.getPoint());
				if (queueIndex < 0) {
					continue;
				}

				String key = detection.getPoint().getX() + ":" + detection.getPoint().getY();
				uniqueRows.putIfAbsent(key, new GatherMarchCandidate(
						gatherType,
						queueIndex,
						readGatherReturnTimeFlow(queueIndex),
						detection.getPoint()));
			}
		}

		rows.addAll(uniqueRows.values());
		rows.sort(Comparator.comparing(GatherMarchCandidate::returnAt).reversed());
		return rows;
	}

	private ImageSearchResultData locatePatternWithMonoFallback(TemplatesEnum template, SearchConfig searchConfig) {
		ImageSearchResultData result = templateSearchHelper.locatePattern(template, searchConfig);
		if (result != null && result.isFound()) {
			return result;
		}
		return templateSearchHelper.locatePatternMono(template, searchConfig);
	}

	private List<ImageSearchResultData> locateAllPatternsWithMonoFallback(TemplatesEnum template, SearchConfig searchConfig) {
		List<ImageSearchResultData> results = templateSearchHelper.locateAllPatterns(template, searchConfig);
		if (results != null && !results.isEmpty()) {
			return results;
		}
		return templateSearchHelper.locateAllPatternsMono(template, searchConfig);
	}

private int recallDuplicateGatherMarchesForSmartProcessingFlow() {
		int recalled = 0;
		int idleMarches = countIdleMarchesFlow();

		while (idleMarches < SMART_PROCESSING_MIN_IDLE_MARCHES_FOR_INTEL) {
			GatherMarchCandidate candidate = findLongestDuplicateGatherMarchFlow();
			if (candidate == null) {
				break;
			}

			if (!recallGatherMarchByQueueFlow(candidate.queueIndex())) {
				break;
			}

			recalled++;
			sleepTask(250);
			idleMarches = countIdleMarchesFlow();
		}

		return recalled;
	}

private GatherMarchCandidate findLongestDuplicateGatherMarchFlow() {
		marchHelper.openLeftMenuCitySection(false);
		try {
			Map<GatherTypeShape, List<GatherMarchCandidate>> groupedByType = new HashMap<>();

			for (GatherTypeShape gatherType : GatherTypeShape.values()) {
				List<ImageSearchResultData> detections = templateSearchHelper.locateAllPatterns(
						gatherType.getTemplate(),
						SearchConfig.builder()
								.withArea(new AreaData(MARCH_QUEUE_REGIONS[0].topLeft(), MARCH_QUEUE_REGIONS[MARCH_QUEUE_REGIONS.length - 1].bottomRight()))
								.withMaxAttempts(3)
								.withDelay(3)
								.withMaxResults(MARCH_QUEUE_REGIONS.length)
								.build());

				for (ImageSearchResultData detection : detections) {
					int queueIndex = findQueueIndexByPointFlow(detection.getPoint());
					if (queueIndex < 0) {
						continue;
					}

					LocalDateTime returnAt = readGatherReturnTimeFlow(queueIndex);
					if (returnAt == null) {
						returnAt = LocalDateTime.now().plusMinutes(5);
					}

					groupedByType.computeIfAbsent(gatherType, key -> new ArrayList<>())
							.add(new GatherMarchCandidate(gatherType, queueIndex, returnAt, detection.getPoint()));
				}
			}

			List<GatherMarchCandidate> duplicates = new ArrayList<>();
			for (List<GatherMarchCandidate> candidates : groupedByType.values()) {
				if (candidates.size() >= 2) {
					duplicates.addAll(candidates);
				}
			}

			if (duplicates.isEmpty()) {
				logInfo(routineLogIntelligenceLine("Smart processing found no duplicate gather marches to recall."));
				return null;
			}

			GatherMarchCandidate selected = duplicates.stream()
					.max(Comparator.comparing(GatherMarchCandidate::returnAt))
					.orElse(null);

			if (selected != null) {
				logInfo(routineLogIntelligenceLine("Smart processing selected duplicate " + selected.type().getName()
						+ " gather march on queue #" + (selected.queueIndex() + 1)
						+ " with longest return time for recall."));
			}

			return selected;
		} finally {
			marchHelper.closeLeftMenu();
		}
	}

private boolean recallGatherMarchByQueueFlow(int queueIndex) {
		marchHelper.openLeftMenuCitySection(false);
		try {
			List<ImageSearchResultData> recallButtons = templateSearchHelper.locateAllPatterns(
					TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
					SearchConfig.builder()
							.withArea(new AreaData(MARCH_QUEUE_REGIONS[0].topLeft(), MARCH_QUEUE_REGIONS[MARCH_QUEUE_REGIONS.length - 1].bottomRight()))
							.withMaxAttempts(3)
							.withDelay(3)
							.withMaxResults(MARCH_QUEUE_REGIONS.length)
							.build());

			if (recallButtons.isEmpty()) {
				return false;
			}

			int targetRowCenterY = (MARCH_QUEUE_REGIONS[queueIndex].topLeft().getY() + MARCH_QUEUE_REGIONS[queueIndex].bottomRight().getY()) / 2;
			ImageSearchResultData bestRowButton = recallButtons.stream()
					.min(Comparator.comparingInt(button -> Math.abs(button.getPoint().getY() - targetRowCenterY)))
					.orElse(null);

			if (bestRowButton == null) {
				return false;
			}

			tapRandomPoint(bestRowButton.getPoint(), bestRowButton.getPoint(), 1, 200);
			tapRandomPoint(MARCH_RECALL_CONFIRM_TOP_LEFT, MARCH_RECALL_CONFIRM_BOTTOM_RIGHT, 1, 200);
			logInfo(routineLogIntelligenceLine("Recalled gather march from queue #" + (queueIndex + 1)
					+ " for smart Intel prioritization."));
			return true;
		} finally {
			marchHelper.closeLeftMenu();
		}
	}

private int countIdleMarchesFlow() {
		marchHelper.openLeftMenuCitySection(false);
		int idleCount = 0;

		try {
			for (int i = 0; i < CommonGameAreas.MARCH_SLOTS_TOP_LEFT.length; i++) {
				PointData topLeft = CommonGameAreas.MARCH_SLOTS_TOP_LEFT[i];
				PointData bottomRight = CommonGameAreas.MARCH_SLOTS_BOTTOM_RIGHT[i];
				String text = emuManager.readText(EMULATOR_NUMBER, topLeft, bottomRight);
				if (text != null && text.toLowerCase().contains("idle")) {
					idleCount++;
				}
			}
		} catch (IOException | TesseractException e) {
			logDebug(routineLogIntelligenceLine("Could not read idle march slots: " + e.getMessage()));
		} finally {
			marchHelper.closeLeftMenu();
		}

		return idleCount;
	}

private int findQueueIndexByPointFlow(PointData point) {
		for (int i = 0; i < MARCH_QUEUE_REGIONS.length; i++) {
			MarchQueueRegion region = MARCH_QUEUE_REGIONS[i];
			if (point.getX() >= region.topLeft().getX() && point.getX() <= region.bottomRight().getX()
					&& point.getY() >= region.topLeft().getY() && point.getY() <= region.bottomRight().getY()) {
				return i;
			}
		}
		return -1;
	}

private LocalDateTime readGatherReturnTimeFlow(int queueIndex) {
		MarchQueueRegion region = MARCH_QUEUE_REGIONS[queueIndex];
		TesseractSettingsData settings = TesseractSettingsData.assembler()
				.pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
				.recognitionEngine(TesseractSettingsData.RecognitionEngine.LSTM_ONLY)
				.stripBackground(true)
				.setTextColor(new Color(255, 255, 255))
				.charWhitelist("0123456789:")
				.build();

		return textHelper.attemptRecognition(
				region.timeTextStart(),
				new PointData(region.timeTextStart().getX() + MARCH_QUEUE_TIME_TEXT_WIDTH,
						region.timeTextStart().getY() + MARCH_QUEUE_TIME_TEXT_HEIGHT),
				3,
				200L,
				settings,
				GameTimeUtils::isAcceptedFormat,
				text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));
	}

private record MarchQueueRegion(PointData topLeft, PointData bottomRight, PointData timeTextStart) {
	}

private record GatherMarchCandidate(GatherTypeShape type, int queueIndex, LocalDateTime returnAt, PointData rowPoint) {
	}

private void hydrateConfiguration() {
		this.fcEra = profile.getConfig(ConfigurationKeyEnum.INTEL_FC_ERA_BOOL, Boolean.class);
		this.useSmartProcessing = profile.getConfig(ConfigurationKeyEnum.INTEL_SMART_PROCESSING_BOOL, Boolean.class);
		this.recallGatherTroopsFlow = profile.getConfig(ConfigurationKeyEnum.INTEL_RECALL_GATHER_TROOPS_BOOL,
				Boolean.class);
		this.useFlag = profile.getConfig(ConfigurationKeyEnum.INTEL_USE_FLAG_BOOL, Boolean.class);
		this.flagNumber = useFlag ? profile.getConfig(ConfigurationKeyEnum.INTEL_BEASTS_FLAG_INT, Integer.class) : null;
		this.beastsEnabled = profile.getConfig(ConfigurationKeyEnum.INTEL_BEASTS_BOOL, Boolean.class);
		this.fireBeastsEnabled = profile.getConfig(ConfigurationKeyEnum.INTEL_FIRE_BEAST_BOOL, Boolean.class);
		this.survivorCampsEnabled = profile.getConfig(ConfigurationKeyEnum.INTEL_CAMP_BOOL, Boolean.class);
		this.explorationsEnabled = profile.getConfig(ConfigurationKeyEnum.INTEL_EXPLORATION_BOOL, Boolean.class);
		this.textHelper = new ResilientOcrExecutor<>(provider);

		logDebug(routineLogIntelligenceLine("Configuration loaded: fcEra=" + fcEra + ", useSmartProcessing=" + useSmartProcessing +
				", recallGatherTroopsFlow=" + recallGatherTroopsFlow + ", useFlag=" + useFlag + ", beastsEnabled="
				+ beastsEnabled));
	}

private boolean hasEnoughStaminaFlow() {
		int staminaValue = StaminaService.getServices().getCurrentStamina(profile.getId());

		if (staminaValue < MIN_STAMINA_REQUIRED_FLOOR) {
			logWarning(routineLogIntelligenceLine("Not enough stamina to process intel. Current stamina: " + staminaValue +
					". Required: " + MIN_STAMINA_REQUIRED_FLOOR + "."));
			long minutesToRegen = (long) (MIN_STAMINA_REQUIRED_FLOOR - staminaValue) * 5L;
			LocalDateTime rescheduleTime = LocalDateTime.now().plusMinutes(minutesToRegen);
			reschedule(rescheduleTime);
			return false;
		}
		return true;
	}

private void manageRescheduling(boolean anyIntelProcessed, boolean nonBeastIntelProcessed,
			MarchesAvailable marchesAvailable) {
		sleepTask(500);

		if (!anyIntelProcessed) {


			tryRescheduleFromCooldownFlow();

			processingTask = false;
			return;
		}


		if (marchQueueLimitReached && nonBeastIntelProcessed) {
			if (recallGatherTroopsFlow) {
				// Changed by pernerch | Date: 2026-07-02 | Why: with Intel recall enabled, immediately
				// free marches and continue processing instead of waiting for a delayed retry.
				logInfo(routineLogIntelligenceLine("March queue full after non-beast Intel while recall is enabled. Recalling gather troops and continuing Intel now."));
				recallGatherTroopsFlow();
				marchQueueLimitReached = false;
				return;
			}


			reschedule(LocalDateTime.now().plusMinutes(2));
			logInfo(routineLogIntelligenceLine("Non-beast intel processed but march queue full. " +
					"Planning next run in 2 minutes to check for more."));

			processingTask = false;
			return;
		}

		if (marchQueueLimitReached && !nonBeastIntelProcessed && !beastMarchSent) {


			if (useSmartProcessing && marchesAvailable.rescheduleTo() != null) {
				reschedule(marchesAvailable.rescheduleTo());
				logInfo(routineLogIntelligenceLine("March queue is full, and only beasts remain. Planning next run for when marches will be available at "
						+ marchesAvailable.rescheduleTo()));
			} else {
				reschedule(LocalDateTime.now().plusMinutes(2));
				logInfo(routineLogIntelligenceLine("March queue is full, and only beasts remain. Planning next run in 2 minutes"));
			}

			processingTask = false;
			return;
		}

		if (!beastMarchSent || marchQueueLimitReached) {


			reschedule(LocalDateTime.now().plusMinutes(2));
			logInfo(routineLogIntelligenceLine("Planning next run in 2 minutes to check if any intel got skipped. " +
					"Beast march sent: " + beastMarchSent + ", March queue full: " + marchQueueLimitReached));

			processingTask = false;
			return;
		}


		logInfo(routineLogIntelligenceLine("Beast march sent successfully. Continuing processing."));
	}

private boolean handleBeastIntel() {
		intelScreenHelper.ensureOnIntelScreen();
		boolean beastFound = false;


		if (fireBeastsEnabled && !(useFlag && beastMarchSent)) {
			logInfo(routineLogIntelligenceLine("Scanning for fire beasts."));
			if (seekAndProcessGrayscale(TemplatesEnum.INTEL_FIRE_BEAST, this::handleBeast)) {
				beastFound = true;
				if (useFlag) {
					return true;

				}
			}
		}


		if (!(useFlag && beastMarchSent)) {
			logInfo(routineLogIntelligenceLine("Scanning for beasts using grayscale matching."));
			TemplatesEnum[] beast_screenings;
			if (fcEra) {


				beast_screenings = new TemplatesEnum[] {
						TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC,
						TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC1,
				};
			} else {


				beast_screenings = new TemplatesEnum[] {
						TemplatesEnum.INTEL_BEAST_GRAYSCALE
				};
			}

			for (TemplatesEnum beast_screening : beast_screenings) {
				if (seekAndProcessGrayscale(beast_screening, this::handleBeast)) {
					beastFound = true;
					break;
				}
			}
		}

		return beastFound;
	}

private void handleSurvivor(ImageSearchResultData result) {
		tapPoint(result.getPoint());
		sleepTask(2000);

		ImageSearchResultData view = templateSearchHelper.locatePattern(TemplatesEnum.INTEL_VIEW, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!view.isFound()) {
			logWarning(routineLogIntelligenceLine("Could not find the 'View' button for the survivor. Going back."));
			pressBack();
			return;
		}

		tapPoint(view.getPoint());
		sleepTask(500);

		ImageSearchResultData rescue = templateSearchHelper.locatePattern(TemplatesEnum.INTEL_RESCUE, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!rescue.isFound()) {
			logWarning(routineLogIntelligenceLine("Could not find the 'Rescue' button for the survivor. Going back."));
			pressBack();
			pressBack();

			return;
		}

		tapPoint(rescue.getPoint());
		sleepTask(500);
		StaminaService.getServices().subtractStamina(profile.getId(), SURVIVOR_STAMINA_COST_VALUE);
		StatisticsService.obtain().addToCounter(profile, "Intel Survivor Camps", 1);
	}

private void handleJourney(ImageSearchResultData result) {
		tapPoint(result.getPoint());
		sleepTask(2000);

		ImageSearchResultData view = templateSearchHelper.locatePattern(TemplatesEnum.INTEL_VIEW, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!view.isFound()) {
			logWarning(routineLogIntelligenceLine("Could not find the 'View' button for the journey. Going back."));
			pressBack();
			return;
		}

		tapPoint(view.getPoint());
		sleepTask(500);

		ImageSearchResultData explore = templateSearchHelper.locatePattern(TemplatesEnum.INTEL_EXPLORE, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!explore.isFound()) {
			logWarning(routineLogIntelligenceLine("Could not find the 'Explore' button for the journey. Going back."));
			pressBack();
			pressBack();

			return;
		}

		tapPoint(explore.getPoint());
		sleepTask(500);
		tapPoint(new PointData(520, 1200));
		sleepTask(1000);
		pressBack();
		StaminaService.getServices().subtractStamina(profile.getId(), JOURNEY_STAMINA_COST_VALUE);
		StatisticsService.obtain().addToCounter(profile, "Intel Journeys", 1);
	}

private void handleBeast(ImageSearchResultData beast) {
		if (marchQueueLimitReached) {
			logInfo(routineLogIntelligenceLine("Beast detected but march queue is full. Skipping deployment but marking as detected."));
			return;
		}

		if (useFlag && beastMarchSent) {
			logInfo(routineLogIntelligenceLine("Beast march already sent with flag. Skipping beast hunt."));
			return;
		}

		tapPoint(beast.getPoint());
		sleepTask(2000);

		ImageSearchResultData view = templateSearchHelper.locatePattern(TemplatesEnum.INTEL_VIEW, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!view.isFound()) {
			logWarning(routineLogIntelligenceLine("Could not find the 'View' button for the beast. Going back."));
			pressBack();
			return;
		}
		tapPoint(view.getPoint());
		sleepTask(500);

		ImageSearchResultData attack = templateSearchHelper.locatePattern(TemplatesEnum.INTEL_ATTACK, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!attack.isFound()) {
			logWarning(routineLogIntelligenceLine("Could not find the 'Attack' button for the beast. Going back."));
			pressBack();
			pressBack();

			return;
		}
		tapPoint(attack.getPoint());
		sleepTask(500);


		ImageSearchResultData deployButton = templateSearchHelper.locatePattern(TemplatesEnum.DEPLOY_BUTTON,
				SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!deployButton.isFound()) {
			logError(routineLogIntelligenceLine("March queue is full. Cannot start a new march."));
			marchQueueLimitReached = true;
			return;
		}


		if (useFlag) {
			marchHelper.selectFlag(flagNumber);
		}


		ImageSearchResultData equalizeButton = templateSearchHelper.locatePattern(TemplatesEnum.RALLY_EQUALIZE_BUTTON,
				SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (equalizeButton.isFound()) {
			tapPoint(equalizeButton.getPoint());
			sleepTask(300);
		}


		long travelTimeSeconds = staminaHelper.parseTravelTime();


		Integer spentStamina = staminaHelper.getSpentStamina();


		ImageSearchResultData deploy = templateSearchHelper.locatePattern(TemplatesEnum.DEPLOY_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!deploy.isFound()) {
			logError(routineLogIntelligenceLine("Deploy button not detected. Planning next run to try again in 5 minutes."));
			reschedule(LocalDateTime.now().plusMinutes(5));
			processingTask = false;

			return;
		}

		tapPoint(deploy.getPoint());
		sleepTask(1000);


		ImageSearchResultData confirmDialog = templateSearchHelper.locatePattern(TemplatesEnum.DEPLOY_CONFIRMATION_DIALOG, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (confirmDialog.isFound()) {
			logInfo(routineLogIntelligenceLine("Deployment confirmation dialog detected (troop imbalance). Confirming deployment."));
			tapPoint(new PointData(211, 713));
			sleepTask(300);
			tapPoint(new PointData(509, 789));
			sleepTask(300);
		}


		deploy = templateSearchHelper.locatePattern(TemplatesEnum.DEPLOY_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (deploy.isFound()) {
			logWarning(routineLogIntelligenceLine("Deploy button still present after deployment attempt. March may have did not complete. Planning next run in 5 minutes."));
			reschedule(LocalDateTime.now().plusMinutes(5));
			processingTask = false;

			return;
		}

		logInfo(routineLogIntelligenceLine("Beast march deployed finished cleanly."));
		beastMarchSent = true;
		StatisticsService.obtain().addToCounter(profile, "Intel Beast", 1);


		staminaHelper.subtractStamina(spentStamina, false);


		if (travelTimeSeconds <= 0) {
			logError(routineLogIntelligenceLine("Could not parse travel time via OCR. Using 5 minute fallback reschedule."));
			LocalDateTime rescheduleTime = LocalDateTime.now().plusMinutes(5);
			reschedule(rescheduleTime);
			processingTask = false;

			return;
		}

		if (useSmartProcessing) {
			LocalDateTime rescheduleTime = LocalDateTime.now().plusSeconds(travelTimeSeconds);
			reschedule(rescheduleTime);
			logInfo(routineLogIntelligenceLine("Beast march scheduled to return at " + GameTimeUtils.formatCountdown(rescheduleTime)));
			processingTask = false;

		}
	}
}
