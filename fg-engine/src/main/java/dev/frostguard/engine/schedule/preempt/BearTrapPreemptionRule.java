package dev.frostguard.engine.schedule.preempt;

import java.time.LocalDateTime;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;

/**
 * Fires when the Bear Trap running indicator is visible on-screen,
 * causing the scheduler to interrupt the current task and launch the
 * bear-trap routine instead.
 */
public class BearTrapPreemptionRule implements PreemptionRule {

    // Changed by pernerch | Date: 2026-07-02 | Why: suppress repeated triggers to prevent Bear Trap preemption storms.
    private static final long SUPPRESS_DURATION_SECONDS = 30L;
    private LocalDateTime suppressUntil = LocalDateTime.MIN;

    @Override
    public boolean shouldPreempt(EmulatorController controller,
                                 AccountDescriptor profile,
                                 RawImageData screenshot) {
        if (profile == null || profile.getEmulatorNumber() == null) {
            return false;
        }
        // Changed by pernerch | Date: 2026-07-02 | Why: preempt only when Bear Trap is enabled for this profile.
        if (Boolean.FALSE.equals(profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, Boolean.class))) {
            return false;
        }
        if (LocalDateTime.now().isBefore(suppressUntil)) {
            return false;
        }
        try {
            ImageSearchResultData match = controller.locatePattern(
                    profile.getEmulatorNumber(), screenshot,
                    TemplatesEnum.BEAR_HUNT_IS_RUNNING, 90);
            if (!match.isFound()) {
                return false;
            }
            suppressUntil = LocalDateTime.now().plusSeconds(SUPPRESS_DURATION_SECONDS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public TpDailyTaskEnum getTaskToExecute() { return TpDailyTaskEnum.BEAR_TRAP; }

    @Override
    public String getRuleName() { return "BearTrapActive"; }
}
