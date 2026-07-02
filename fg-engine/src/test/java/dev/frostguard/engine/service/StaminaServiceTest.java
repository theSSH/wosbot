package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class StaminaServiceTest {

    // Changed by pernerch | Date: 2026-07-02 | Why: protect against over-reported stamina entering runtime state.
    @Test
    void setStaminaCapsValuesAboveTheGameLimit() {
        StaminaService service = StaminaService.getServices();

        service.setStamina(1001L, 250);

        assertEquals(200, service.getCurrentStamina(1001L));
    }

    // Changed by pernerch | Date: 2026-07-02 | Why: ensure additive updates cannot exceed the stamina cap.
    @Test
    void addStaminaDoesNotExceedTheGameLimit() {
        StaminaService service = StaminaService.getServices();

        service.setStamina(1002L, 190);
        service.addStamina(1002L, 25);

        assertEquals(200, service.getCurrentStamina(1002L));
    }
}
