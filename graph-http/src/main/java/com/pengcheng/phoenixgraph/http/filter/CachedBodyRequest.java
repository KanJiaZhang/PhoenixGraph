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
 * The copy is bounded by {@code phoenixgraph.limits.max-body-bytes}, and a body over the limit is refused with a
 * 413 instead of being buffered: "we copied it anyway" is how the one filter that is supposed to protect the
 * instance becomes the reason it runs out of heap. (The earlier version of this class claimed to be bounded and
 * then buffered everything - the bound is real now, and the verification suite sends a 2 MiB body to prove it.)
 */
class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final boolean buffered;

    /**
     * Reads the body, or refuses it.
     *
     * @param request the request whose body to copy
     * @param maxBytes the largest body to accept
     * @throws BodyTooLargeException when the body is larger than {@code maxBytes} (the caller answers 413)
     */
    CachedBodyRequest(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        // the declared length is checked first, so an oversized upload is refused without reading it; a chunked
        // body (no Content-Length) still gets read, and its size is checked below
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            throw new BodyTooLargeException(declared, maxBytes);
        }
        byte[] raw = request.getInputStream().readAllBytes();
        if (raw.length > maxBytes) {
            throw new BodyTooLargeException(raw.length, maxBytes);
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
