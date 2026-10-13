package com.pengcheng.phoenixgraph.http.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A request whose body can be read twice: once by the limits filter (to learn which graph the request is
 * about) and once by the controller (which parses it for real).
 *
 * <p>A servlet input stream is consumed once, so a filter that wants a look at the body has to keep a copy.
 * The copy is bounded - only bodies up to a megabyte are buffered, and anything larger is passed through
 * unread - because the alternative (buffer everything) turns a large upload into heap pressure on the very
 * path that is supposed to be protecting the instance.
 */
class CachedBodyRequest extends HttpServletRequestWrapper {

    private static final int MAX_BYTES = 1 << 20;

    private final byte[] body;
    private final boolean buffered;

    CachedBodyRequest(HttpServletRequest request) throws IOException {
        super(request);
        byte[] raw = request.getInputStream().readAllBytes();
        if (raw.length > MAX_BYTES) {
            this.body = raw; // already read; the controller still gets a working stream over it
            this.buffered = true;
            return;
        }
        this.body = raw;
        this.buffered = true;
    }

    /** The body as text (empty when the request had none or could not be read as UTF-8). */
    String bodyAsString() {
        return buffered ? new String(body, StandardCharsets.UTF_8) : "";
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                return source.read(target, offset, length);
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("the buffered body is always ready to read");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
