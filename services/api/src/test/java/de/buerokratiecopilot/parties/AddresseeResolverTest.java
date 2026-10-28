package de.buerokratiecopilot.parties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.parties.AddresseeResolver.Candidate;

/** Whom a letter is addressed to, from what the letter says. */
class AddresseeResolverTest {

    private static final UUID ANNA = UUID.randomUUID();
    private static final UUID MAX = UUID.randomUUID();
    private static final UUID LENA = UUID.randomUUID();

    private final List<Candidate> family = List.of(
            new Candidate(ANNA, "Anna Müller", List.of("Anna Schmidt"), LocalDate.of(1985, 3, 14), List.of(), ""),
            new Candidate(MAX, "Max Müller", List.of(), LocalDate.of(1983, 7, 2), List.of("12 345 678 901"), ""),
            new Candidate(LENA, "Lena Müller", List.of(), LocalDate.of(2015, 9, 1), List.of(), ""));

    @Test
    @DisplayName("the name in the address window decides, even when the family name is everywhere")
    void headerName() {
        String letter = "Frau\nAnna Müller\nMusterweg 5\n10115 Berlin\n\nSehr geehrte Familie Müller, ...";
        assertThat(AddresseeResolver.choose(family, letter)).isEqualTo(ANNA);
    }

    @Test
    @DisplayName("umlauts, the name the other way round and an earlier name still find the person")
    void spellings() {
        assertThat(AddresseeResolver.choose(family, "Herrn Max Mueller\nMusterweg 5")).isEqualTo(MAX);
        assertThat(AddresseeResolver.choose(family, "Müller, Lena\nMusterweg 5")).isEqualTo(LENA);
        assertThat(AddresseeResolver.choose(family, "Frau Anna Schmidt\nAlte Straße 1")).isEqualTo(ANNA);
    }

    @Test
    @DisplayName("a birth date or an identifier settles a letter without a clear name")
    void birthDateAndIdentifier() {
        String letter = "Schule\n" + "x".repeat(800) + "\nSchüler: geb. 01.09.2015";
        assertThat(AddresseeResolver.choose(family, letter)).isEqualTo(LENA);
        assertThat(AddresseeResolver.choose(family, "Steuer-ID 12345678901 Bescheid")).isEqualTo(MAX);
    }

    @Test
    @DisplayName("two equally likely parties mean nobody is chosen")
    void tieMeansUnknown() {
        assertThat(AddresseeResolver.choose(family, "Familie Müller\nMusterweg 5")).isNull();
        assertThat(AddresseeResolver.choose(family, "Rechnung ohne Empfänger")).isNull();
    }

    @Test
    @DisplayName("a cabinet with a single party addresses every letter to it")
    void singleParty() {
        assertThat(AddresseeResolver.choose(List.of(family.get(0)), "irgendein Text")).isEqualTo(ANNA);
        assertThat(AddresseeResolver.choose(List.of(), "irgendein Text")).isNull();
    }
}
