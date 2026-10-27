package com.trs.rag.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.app.rag.ingestion.TextBodyLimitFilter;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

/**
 * Pasted JSON is bounded even when the sender omits Content-Length.
 *
 * @author agent
 */
class TextBodyLimitTest {
  @Test
  void forwardsSmallBodiesUnchanged() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/knowledge-bases/k/documents/text");
    byte[] data = "{\"text\":\"正文🙂\"}".getBytes(StandardCharsets.UTF_8);
    request.setContent(data);
    var response = new MockHttpServletResponse();
    var called = new AtomicBoolean();
    new TextBodyLimitFilter()
        .doFilter(
            request,
            response,
            (req, res) -> {
              assertArrayEquals(data, req.getInputStream().readAllBytes());
              called.set(true);
            });
    assertTrue(called.get());
  }

  @Test
  void rejectsKnownOversizedBodiesBeforeDeserialization() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/knowledge-bases/k/documents/text");
    request.setContent(new byte[TextBodyLimitFilter.LIMIT + 1]);
    var response = new MockHttpServletResponse();
    var called = new AtomicBoolean();
    new TextBodyLimitFilter().doFilter(request, response, (req, res) -> called.set(true));
    assertEquals(413, response.getStatus());
    assertFalse(called.get());
  }

  @Test
  void alsoRejectsChunkedOversizedBodies() throws Exception {
    var original = new MockHttpServletRequest("POST", "/api/knowledge-bases/k/documents/text");
    original.setContent(new byte[TextBodyLimitFilter.LIMIT + 1]);
    var request =
        new HttpServletRequestWrapper(original) {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    var response = new MockHttpServletResponse();
    var called = new AtomicBoolean();
    new TextBodyLimitFilter().doFilter(request, response, (req, res) -> called.set(true));
    assertEquals(413, response.getStatus());
    assertFalse(called.get());
  }
}
