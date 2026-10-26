package com.litemask.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RestoreGrowthBudgetTest {

    @Test
    @DisplayName("还原增长在多个重复令牌间累计，超额前就拒绝")
    void repeatedTokensShareOneGrowthBudget() {
        TaskContext context = TaskContext.create(null, 100);
        String mappedValue = "A".repeat(60);

        assertThrows(TaskContext.RestoreBudgetExceededException.class,
                () -> TaskContext.in(context, () -> {
                    RedactionEngine.restoreCounted("<x><x>", Map.of("<x>", mappedValue));
                    return null;
                }));
    }
}
