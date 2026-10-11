package de.buerokratiecopilot.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Easter algorithm against published dates (spec §13.2).
 *
 * <p>Easter is the one movable date everything else hangs off, so it is worth checking against known
 * values rather than against itself. The years below include a century year and a leap year, which are
 * where a wrong implementation goes wrong.</p>
 */
class EasterCalculatorTest {

    @ParameterizedTest(name = "Easter {0} is {1}")
    @CsvSource({
        "2024, 2024-03-31",
        "2025, 2025-04-20",
        "2026, 2026-04-05",
        "2027, 2027-03-28",
        "2028, 2028-04-16",
        "2030, 2030-04-21",
        "2000, 2000-04-23",
        "1900, 1900-04-15",
    })
    @DisplayName("Easter Sunday matches the published date")
    void computesEaster(int year, String expected) {
        assertThat(EasterCalculator.easterSunday(year)).isEqualTo(LocalDate.parse(expected));
    }

    @Test
    @DisplayName("Easter never falls before 22 March or after 25 April")
    void easterStaysInItsRange() {
        for (int year = 1900; year <= 2100; year++) {
            LocalDate easter = EasterCalculator.easterSunday(year);
            assertThat(easter).isBetween(LocalDate.of(year, 3, 22), LocalDate.of(year, 4, 25));
            assertThat(easter.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SUNDAY);
        }
    }

    @Test
    @DisplayName("movable feasts are offsets from Easter, offered as candidates rather than holidays")
    void offersMovableCandidates() {
        var candidates = EasterCalculator.movableCandidates(2026);

        assertThat(candidates).extracting(EasterCalculator.MovableFeast::name)
                .contains("Karfreitag", "Ostermontag", "Pfingstmontag");
        assertThat(candidates.stream()
                .filter(feast -> feast.name().equals("Karfreitag")).findFirst().orElseThrow().date())
                .isEqualTo(LocalDate.of(2026, 4, 3));
        assertThat(candidates.stream()
                .filter(feast -> feast.name().equals("Pfingstmontag")).findFirst().orElseThrow().date())
                .isEqualTo(LocalDate.of(2026, 5, 25));
    }

    @Test
    @DisplayName("a date is recognised as movable only when it is one of the offsets")
    void recognisesMovableOffsets() {
        assertThat(EasterCalculator.offsetFromEaster(LocalDate.of(2026, 4, 3))).isEqualTo(-2);
        assertThat(EasterCalculator.offsetFromEaster(LocalDate.of(2026, 5, 25))).isEqualTo(50);
        assertThat(EasterCalculator.offsetFromEaster(LocalDate.of(2026, 4, 10))).isNull();
        assertThat(EasterCalculator.offsetFromEaster(null)).isNull();
    }
}
