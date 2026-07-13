package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class StaminaServiceTest {

    @Test
    void setStaminaKeepsOverfilledValues() {
        StaminaService service = StaminaService.getServices();

        service.setStamina(1001L, 250);

        assertEquals(250, service.getCurrentStamina(1001L));
    }

    @Test
    void addStaminaCanOverfillAbovePassiveRegenLimit() {
        StaminaService service = StaminaService.getServices();

        service.setStamina(1002L, 190);
        service.addStamina(1002L, 25);

        assertEquals(215, service.getCurrentStamina(1002L));
    }

    @Test
    void subtractStaminaDoesNotGoBelowZero() {
        StaminaService service = StaminaService.getServices();

        service.setStamina(1003L, 10);
        service.subtractStamina(1003L, 25);

        assertEquals(0, service.getCurrentStamina(1003L));
    }
}
