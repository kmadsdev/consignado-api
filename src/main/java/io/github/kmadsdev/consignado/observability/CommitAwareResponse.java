package io.github.kmadsdev.consignado.observability;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

/**
 * Runs a callback once, just before the response can be committed: on the first byte or
 * character of the body, on a flush, on {@code sendError} or {@code sendRedirect}. Headers
 * set in the callback still reach the client. Spring writes a JSON body and flushes it inside
 * the handler, so a filter that sets headers after {@code chain.doFilter} would be too late.
 *
 * <p>The body is not buffered. {@link #beforeCommit()} is also called by the filter at the
 * end, for responses without a body (204, 201 with no content, a HEAD request).
 */
final class CommitAwareResponse extends HttpServletResponseWrapper {

    private final Runnable callback;
    private boolean done;
    private ServletOutputStream stream;
    private PrintWriter writer;

    CommitAwareResponse(HttpServletResponse response, Runnable callback) {
        super(response);
        this.callback = callback;
    }

    void beforeCommit() {
        if (!done) {
            done = true;
            callback.run();
        }
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (stream == null) {
            stream = new Stream(super.getOutputStream());
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            writer = new PrintWriter(new FirstWrite(super.getWriter()));
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        beforeCommit();
        super.flushBuffer();
    }

    @Override
    public void sendError(int sc) throws IOException {
        beforeCommit();
        super.sendError(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        beforeCommit();
        super.sendError(sc, msg);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        beforeCommit();
        super.sendRedirect(location);
    }

    /** A reset clears the headers; they are written again before the next commit. */
    @Override
    public void reset() {
        super.reset();
        done = false;
    }

    private final class Stream extends ServletOutputStream {
        private final ServletOutputStream delegate;

        Stream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            beforeCommit();
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            beforeCommit();
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            beforeCommit();
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            beforeCommit();
            delegate.close();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
        }
    }

    private final class FirstWrite extends Writer {
        private final Writer delegate;

        FirstWrite(Writer delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(char[] buffer, int off, int len) throws IOException {
            beforeCommit();
            delegate.write(buffer, off, len);
        }

        @Override
        public void flush() throws IOException {
            beforeCommit();
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            beforeCommit();
            delegate.close();
        }
    }
}
