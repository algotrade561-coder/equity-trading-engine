package com.equity.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * Archives to S3. What the instance uses; credentials come from the instance role, never from
 * configuration.
 *
 * <p>An S3 put is atomic — an object is either wholly there or not — and the SDK sends a checksum
 * with every upload that S3 verifies before it acknowledges. On top of that the object is read back
 * with a HEAD and its size compared to the file, so "the call returned" and "the bytes are there"
 * are the same statement before the caller deletes anything.</p>
 */
public final class S3CandleArchive implements CandleArchive {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3CandleArchive(S3Client s3, String bucket, String prefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    @Override
    public String store(LocalDate tradingDate, Path file, long rows) throws IOException {
        String key = prefix + "/" + tradingDate.getYear() + "/" + CandleArchiver.fileName(tradingDate);
        long size = Files.size(file);
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .contentType("text/csv").contentEncoding("gzip")
                            .serverSideEncryption(ServerSideEncryption.AES256)
                            .metadata(Map.of("trading-date", tradingDate.toString(), "rows", Long.toString(rows)))
                            .build(),
                    file);
            HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key(key));
            if (head.contentLength() != size) {
                throw new IOException("s3://" + bucket + "/" + key + " is " + head.contentLength()
                        + " bytes after upload, expected " + size);
            }
        } catch (SdkException e) {
            throw new IOException("upload to s3://" + bucket + "/" + key + " failed: " + e.getMessage(), e);
        }
        return "s3://" + bucket + "/" + key;
    }

    @Override
    public String describe() {
        return "s3://" + bucket + "/" + prefix + "/";
    }
}
