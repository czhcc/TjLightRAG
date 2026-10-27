package com.trs.app.rag.ingestion;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds pasted JSON before Jackson allocation, after the standard security chain.
 *
 * @author agent
 */
@Component
@Order(0)
public class TextBodyLimitFilter extends OncePerRequestFilter {
  public static final int LIMIT = 6 * 1024 * 1024;

  /**
   * Buffers only the bounded text endpoint; multipart remains streaming under Spring's own cap.
   *
   * @author agent
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request
            .getRequestURI()
            .substring(request.getContextPath().length())
            .startsWith("/api/knowledge-bases/")
        || !request.getMethod().equals("POST")
        || !request.getRequestURI().endsWith("/documents/text")) {
      chain.doFilter(request, response);
      return;
    }
    if (request.getContentLengthLong() > LIMIT) {
      tooLarge(response);
      return;
    }
    byte[] bytes = request.getInputStream().readNBytes(LIMIT + 1);
    if (bytes.length > LIMIT) {
      tooLarge(response);
      return;
    }
    chain.doFilter(new BufferedRequest(request, bytes), response);
  }

  private void tooLarge(HttpServletResponse response) throws IOException {
    response.setStatus(413);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"error\":\"document_too_large\"}");
  }

  /**
   * Replays a validated bounded body without retaining it outside the request.
   *
   * @author agent
   */
  private static class BufferedRequest extends HttpServletRequestWrapper {
    private final byte[] bytes;

    BufferedRequest(HttpServletRequest original, byte[] bytes) {
      super(original);
      this.bytes = bytes;
    }

    /**
     * Provides the bounded request stream to the normal message converter.
     *
     * @author agent
     */
    @Override
    public ServletInputStream getInputStream() {
      var input = new ByteArrayInputStream(bytes);
      return new ServletInputStream() {
        /** Reads one buffered byte. @author agent */
        @Override
        public int read() {
          return input.read();
        }

        /** Reads buffered bytes. @author agent */
        @Override
        public int read(byte[] target, int offset, int length) {
          return input.read(target, offset, length);
        }

        /** Reports buffered stream completion. @author agent */
        @Override
        public boolean isFinished() {
          return input.available() == 0;
        }

        /** Buffered input is synchronously ready. @author agent */
        @Override
        public boolean isReady() {
          return true;
        }

        /** Async reads are not used by this synchronous JSON endpoint. @author agent */
        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Synchronous bounded input");
        }
      };
    }

    /**
     * Exposes the same bounded UTF-8 body to character readers.
     *
     * @author agent
     */
    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
