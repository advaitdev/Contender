package me.advait.contender.hacker;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HackSettingsTest {
    @Test void defaultsAreAllOff() {
        HackSettings settings = HackSettings.defaults();
        assertTrue(settings.active().isEmpty());
        assertEquals("No hacks", settings.summary());
    }

    @Test void clampsSavedValuesIntoRange() {
        HackSettings settings = new HackSettings(Map.of(HackSetting.REACH, 999.0, HackSetting.MOVEMENT_SPEED, Double.NaN));
        assertEquals(HackSetting.REACH.max, settings.get(HackSetting.REACH));
        assertEquals(HackSetting.MOVEMENT_SPEED.normal, settings.get(HackSetting.MOVEMENT_SPEED));
    }

    @Test void presetLevelsRoundTrip() {
        for (HackSetting setting : HackSetting.values()) {
            for (HackSetting.Level level : HackSetting.Level.values()) {
                assertEquals(level, setting.levelOf(HackSettings.all(level).get(setting)), setting + " " + level);
            }
            assertTrue(setting.isOff(setting.value(HackSetting.Level.OFF)), setting + " off preset should be off");
        }
    }

    @Test void closetSetIsSubtle() {
        HackSettings closet = HackSettings.closet();
        assertEquals(4, closet.active().size());
        assertTrue(closet.warnings().isEmpty());
    }

    @Test void withChangesOneValue() {
        HackSettings settings = HackSettings.defaults().with(HackSetting.REACH, HackSetting.REACH.value(HackSetting.Level.STRONG));
        assertEquals(1, settings.active().size());
        assertTrue(settings.summary().startsWith(HackSetting.REACH.label));
    }
}
