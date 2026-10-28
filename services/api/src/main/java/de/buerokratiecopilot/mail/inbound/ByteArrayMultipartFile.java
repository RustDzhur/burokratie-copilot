package de.buerokratiecopilot.mail.inbound;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.web.multipart.MultipartFile;

/**
 * A mail attachment presented as an uploaded file, so that it enters the platform through the same door as
 * every other file ({@code DocumentService.receive}) instead of through a second, weaker one.
 */
final class ByteArrayMultipartFile implements MultipartFile {

    private final String filename;
    private final String contentType;
    private final byte[] content;

    ByteArrayMultipartFile(String filename, String contentType, byte[] content) {
        this.filename = filename;
        this.contentType = contentType;
        this.content = content;
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return filename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public byte[] getBytes() {
        return content;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(File destination) throws IOException {
        Files.write(destination.toPath(), content);
    }

    @Override
    public void transferTo(Path destination) throws IOException {
        Files.write(destination, content);
    }
}
