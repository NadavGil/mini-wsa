package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.AttackCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class AttackTypeMapperTest {

    private AttackTypeMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AttackTypeMapper();
    }

    @ParameterizedTest
    @EnumSource(AttackCategory.class)
    void allCategoriesShouldHaveNonNullMapping(AttackCategory category) {
        String type = mapper.map(category);
        assertThat(type).isNotNull().isNotBlank();
    }

    @Test
    void injectionShouldMapToSqlInjection() {
        assertThat(mapper.map(AttackCategory.INJECTION)).isEqualTo("SQL/Command Injection");
    }

    @Test
    void xssShouldMapCorrectly() {
        assertThat(mapper.map(AttackCategory.XSS)).isEqualTo("Cross-Site Scripting");
    }

    @Test
    void nullCategoryShouldMapToUnknown() {
        assertThat(mapper.map(null)).isEqualTo("Unknown");
    }

    @Test
    void dosShouldMapToDoS() {
        assertThat(mapper.map(AttackCategory.DOS)).isEqualTo("Denial of Service");
    }

    @Test
    void botShouldMapToBot() {
        assertThat(mapper.map(AttackCategory.BOT)).isEqualTo("Bot Activity");
    }
}
