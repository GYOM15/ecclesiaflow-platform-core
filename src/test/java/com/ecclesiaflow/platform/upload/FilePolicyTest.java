package com.ecclesiaflow.platform.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePolicyTest {

    @Test
    @DisplayName("a size cap of zero or less is refused")
    void refusesANonPositiveCap() {
        assertThatThrownBy(() -> new FilePolicy(0, Set.of(MagicBytes.TEXT_CSV)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBytes");
    }

    @Test
    @DisplayName("the spreadsheet import takes CSV and XLSX, up to 5 MB")
    void spreadsheetImport() {
        FilePolicy policy = FilePolicy.spreadsheetImport();

        assertThat(policy.allowedTypes()).containsExactlyInAnyOrder(MagicBytes.TEXT_CSV, MagicBytes.XLSX);
        assertThat(policy.maxBytes()).isEqualTo(5L * 1024 * 1024);
    }
}
