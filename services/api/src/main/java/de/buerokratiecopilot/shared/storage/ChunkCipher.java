package de.buerokratiecopilot.shared.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Streaming AES-256-GCM in chunks, for documents of any size without holding them in memory.
 *
 * <p>Format: {@code "BCE1"}, one byte key version, an 8 byte random prefix, then chunks of up to 64 KiB of plaintext, each sealed
 * on its own with the nonce {@code prefix || chunk number}. The one byte of additional authenticated data tells whether the chunk is
 * the last one, so a file cut at a chunk boundary, chunks swapped, or a byte changed all fail the check instead of yielding a
 * shorter or altered document.</p>
 */
public final class ChunkCipher {

    public static final byte[] MAGIC = { 'B', 'C', 'E', '1' };
    public static final int CHUNK = 64 * 1024;
    static final int TAG = 16;
    private static final int HEADER = MAGIC.length + 1 + 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ChunkCipher() {
    }

    /** The size of the sealed file for a plaintext of the given size. */
    public static long sealedSize(long plainSize) {
        long chunks = Math.max(1, (plainSize + CHUNK - 1) / CHUNK);
        return HEADER + plainSize + chunks * TAG;
    }

    private static Cipher cipher(int mode, SecretKey key, byte[] prefix, int counter, boolean last) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        System.arraycopy(prefix, 0, nonce, 0, 8);
        nonce[8] = (byte) (counter >>> 24);
        nonce[9] = (byte) (counter >>> 16);
        nonce[10] = (byte) (counter >>> 8);
        nonce[11] = (byte) counter;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG * 8, nonce));
        cipher.updateAAD(new byte[] { (byte) (last ? 1 : 0) });
        return cipher;
    }

    public static boolean isSealed(byte[] head, int length) {
        return length >= MAGIC.length && head[0] == MAGIC[0] && head[1] == MAGIC[1] && head[2] == MAGIC[2] && head[3] == MAGIC[3];
    }

    /** Seals a stream. */
    public static InputStream seal(InputStream plain, SecretKey key, int keyVersion) {
        byte[] prefix = new byte[8];
        RANDOM.nextBytes(prefix);
        byte[] header = new byte[HEADER];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[MAGIC.length] = (byte) keyVersion;
        System.arraycopy(prefix, 0, header, MAGIC.length + 1, 8);
        return new InputStream() {
            private byte[] buffer = header;
            private int position;
            private byte[] current;
            private int counter;
            private boolean finished;

            private boolean fill() throws IOException {
                if (position < buffer.length) {
                    return true;
                }
                if (finished) {
                    return false;
                }
                try {
                    if (current == null) {
                        current = plain.readNBytes(CHUNK);
                    }
                    byte[] lookahead = plain.readNBytes(CHUNK);
                    boolean last = lookahead.length == 0;
                    buffer = cipher(Cipher.ENCRYPT_MODE, key, prefix, counter++, last).doFinal(current);
                    position = 0;
                    current = lookahead;
                    finished = last;
                    return true;
                }
                catch (GeneralSecurityException ex) {
                    throw new IOException("The document could not be encrypted", ex);
                }
            }

            @Override
            public int read() throws IOException {
                return fill() ? buffer[position++] & 0xff : -1;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                if (!fill()) {
                    return -1;
                }
                int n = Math.min(length, buffer.length - position);
                System.arraycopy(buffer, position, target, offset, n);
                position += n;
                return n;
            }

            @Override
            public void close() throws IOException {
                plain.close();
            }
        };
    }

    /** Opens a sealed stream whose first bytes were already read (the magic) and are given back here. */
    public static InputStream open(InputStream sealed, SecretKey key) throws IOException {
        byte[] header = sealed.readNBytes(HEADER);
        if (header.length < HEADER || !isSealed(header, header.length)) {
            throw new IOException("Not an encrypted document");
        }
        byte[] prefix = new byte[8];
        System.arraycopy(header, MAGIC.length + 1, prefix, 0, 8);
        return new InputStream() {
            private byte[] buffer = new byte[0];
            private int position;
            private byte[] frame;
            private int counter;
            private boolean finished;

            private boolean fill() throws IOException {
                if (position < buffer.length) {
                    return true;
                }
                if (finished) {
                    return false;
                }
                try {
                    if (frame == null) {
                        frame = sealed.readNBytes(CHUNK + TAG);
                    }
                    byte[] lookahead = sealed.readNBytes(CHUNK + TAG);
                    boolean last = lookahead.length == 0;
                    if (frame.length < TAG) {
                        throw new IOException("The encrypted document is cut short");
                    }
                    buffer = cipher(Cipher.DECRYPT_MODE, key, prefix, counter++, last).doFinal(frame);
                    position = 0;
                    frame = lookahead;
                    finished = last;
                    return true;
                }
                catch (AEADBadTagException ex) {
                    throw new IOException("The encrypted document has been changed, cut or is read with the wrong key", ex);
                }
                catch (GeneralSecurityException ex) {
                    throw new IOException("The document could not be decrypted", ex);
                }
            }

            @Override
            public int read() throws IOException {
                if (!fill()) {
                    return -1;
                }
                return buffer[position++] & 0xff;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                if (!fill()) {
                    return -1;
                }
                int n = Math.min(length, buffer.length - position);
                System.arraycopy(buffer, position, target, offset, n);
                position += n;
                return n;
            }

            @Override
            public void close() throws IOException {
                sealed.close();
            }
        };
    }

    /** Puts back the bytes that were read to look at the beginning of a stream. */
    static InputStream withHead(byte[] head, int length, InputStream rest) {
        return new java.io.SequenceInputStream(new ByteArrayInputStream(head, 0, length), rest);
    }
}
