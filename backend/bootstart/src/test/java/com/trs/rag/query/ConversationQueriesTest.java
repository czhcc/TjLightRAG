package com.trs.rag.query;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.trs.app.rag.model.ModelFailure;
import com.trs.app.rag.query.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Partial or cancelled model output must never become successful history. @author czhcc */
class ConversationQueriesTest {
    @Test
    void failedStreamPersistsPartialTextWithoutCompletingTheTurn() {
        var service = mock(QueryService.class);
        var store = mock(ConversationService.class);
        var queries = new ConversationQueries(service, store);
        UUID kb = UUID.randomUUID(), conversation = UUID.randomUUID(), id = UUID.randomUUID();
        var request = new QueryRequest();
        request.setQuery("question");
        request.setConversationId(conversation);
        request.setRequestId(id);
        when(store.begin(kb, conversation, "user", id, "question"))
                .thenReturn(new ConversationService.Turn(id, List.of()));
        when(service.stream(eq(kb), eq("user"), eq(request), anyList(), any())).thenAnswer(call -> {
            java.util.function.BiConsumer<String, Object> events = call.getArgument(4);
            events.accept("delta", Map.of("text", "partial"));
            throw new ModelFailure("model_http_error");
        });
        assertThrows(ModelFailure.class, () -> queries.stream(kb, "user", request, (event, value) -> {}));
        verify(store).fail(kb, conversation, "user", id, "partial", false);
        verify(store, never()).complete(any(), any(), anyString(), any(), any());
    }
}
