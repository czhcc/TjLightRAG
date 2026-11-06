package com.trs.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Exact statistics cannot be silently replaced by invented model counts. @author czhcc */
class AgentFinalizerTest {
    @Test
    void validatesCountsAndPreservesLargeDecimalStrings() {
        var stats = List.<Map<String, Object>>of(
                Map.of("result", Map.of("total", "9007199254740993", "count", "9007199254740993")));
        assertDoesNotThrow(() -> AgentFinalizer.validateStatistics("共有9007199254740993个对象。", stats));
        assertThrows(RuntimeException.class, () -> AgentFinalizer.validateStatistics("共有5个对象。", stats));
        assertThrows(RuntimeException.class, () -> AgentFinalizer.validateStatistics("共有五个对象。", stats));
    }
}
