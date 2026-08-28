package com.github.jingyangyu.scmjobnotifier.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CsvUtilTest {

    @Test
    void parsesCommaSeparated() {
        assertThat(CsvUtil.parse("a,b,c")).containsExactly("a", "b", "c");
    }

    @Test
    void trimsAndDropsEmpties() {
        assertThat(CsvUtil.parse(" a , , b ,")).containsExactly("a", "b");
    }

    @Test
    void emptyForNullOrBlank() {
        assertThat(CsvUtil.parse(null)).isEmpty();
        assertThat(CsvUtil.parse("")).isEmpty();
        assertThat(CsvUtil.parse("   ")).isEmpty();
    }

    @Test
    void singleValue() {
        assertThat(CsvUtil.parse("solo")).containsExactly("solo");
    }
}
