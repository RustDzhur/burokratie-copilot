package de.buerokratiecopilot.shared.idempotency;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Request wrapper that keeps the body in memory so it can be read twice: once to fingerprint the
 * request and once by the controller that consumes it.
 *
 * <p>It is only used for small, non-multipart payloads (see {@link IdempotencyFilter}). Document
 * uploads are deliberately excluded: buffering a 100 MiB upload to compute an idempotency hash would
 * violate the streaming requirement of spec §27, and duplicate detection for documents is already
 * solved by the content hash at the document level (task B-004).</p>
 */
class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final Charset charset;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body.clone();
        this.charset = request.getCharacterEncoding() == null
                ? StandardCharsets.UTF_8
                : Charset.forName(request.getCharacterEncoding());
    }

    byte[] body() {
        return body.clone();
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
            public int read(byte[] buffer, int offset, int length) {
                return source.read(buffer, offset, length);
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
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Asynchronous reading is not supported");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
}
