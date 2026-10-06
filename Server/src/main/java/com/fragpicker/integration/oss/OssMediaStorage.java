package com.fragpicker.integration.oss;

import com.aliyun.oss.*;
import com.aliyun.oss.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.fragpicker.integration.oss.OssStorageFailure.Code.*;

/** Internal adapter. Business services must authorize ownership before calling it. */
public class OssMediaStorage {
    private final OSS client;
    private final OssProperties properties;
    private final Clock clock;
    public OssMediaStorage(OSS client, OssProperties properties, Clock clock) {
        properties.validate(); this.client = client; this.properties = properties; this.clock = clock;
    }

    public void verifyPrivateBucket() {
        try {
            if (client.getBucketAcl(properties.bucket()).getCannedACL() != CannedAccessControlList.Private) {
                throw new OssStorageFailure(BUCKET_NOT_PRIVATE, false);
            }
        } catch (OSSException failure) { throw mapped(failure); }
        catch (ClientException failure) { throw new OssStorageFailure(UNAVAILABLE, true); }
    }

    public StoredMedia put(long userId, long fragmentId, MediaKind kind, Path file, String contentType) {
        prefix(userId, fragmentId, kind);
        if (!kind.allows(contentType)) throw new IllegalArgumentException("Unsupported media content type");
        long limit = kind == MediaKind.VIDEO ? properties.maxVideoBytes() : properties.maxCoverBytes();
        var digest = digest(file, limit);
        String key = prefix(userId, fragmentId, kind) + digest.sha256();
        var metadata = new ObjectMetadata();
        metadata.setContentLength(digest.size()); metadata.setContentType(contentType); metadata.setContentMD5(digest.md5());
        metadata.setObjectAcl(CannedAccessControlList.Private);
        metadata.setCacheControl("private, max-age=300");
        metadata.setUserMetadata(Map.of("sha256", digest.sha256()));
        try (var input = Files.newInputStream(file)) {
            client.putObject(new PutObjectRequest(properties.bucket(), key, input, metadata));
            return new StoredMedia(properties.bucket(), key, digest.size(), digest.sha256(), contentType);
        } catch (OSSException failure) { throw mapped(failure); }
        catch (ClientException failure) { throw new OssStorageFailure(UNAVAILABLE, true); }
        catch (IOException failure) { throw new OssStorageFailure(LOCAL_FILE_ERROR, false); }
    }

    public SignedMediaUrl signedGet(long userId, long fragmentId, MediaKind kind, String key) {
        return signedGet(userId, fragmentId, kind, key, properties.signedUrlTtl());
    }

    public SignedMediaUrl signedGet(long userId, long fragmentId, MediaKind kind, String key, Duration ttl) {
        requireOwnedKey(userId, fragmentId, kind, key); OssProperties.validateTtl(ttl);
        Instant expiry = clock.instant().plus(ttl).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var request = new GeneratePresignedUrlRequest(properties.bucket(), key, HttpMethod.GET);
        request.setExpiration(Date.from(expiry));
        try { return new SignedMediaUrl(client.generatePresignedUrl(request).toURI(), expiry); }
        catch (OSSException failure) { throw mapped(failure); }
        catch (ClientException failure) { throw new OssStorageFailure(UNAVAILABLE, true); }
        catch (java.net.URISyntaxException failure) { throw new OssStorageFailure(PROVIDER_REJECTED, false); }
    }

    public void delete(long userId, long fragmentId, MediaKind kind, String key) {
        requireOwnedKey(userId, fragmentId, kind, key);
        try { client.deleteObject(properties.bucket(), key); }
        catch (OSSException failure) { throw mapped(failure); }
        catch (ClientException failure) { throw new OssStorageFailure(UNAVAILABLE, true); }
    }

    private String prefix(long userId, long fragmentId, MediaKind kind) {
        if (userId <= 0 || fragmentId <= 0 || kind == null) throw new IllegalArgumentException("Invalid media owner or kind");
        return "users/" + userId + "/fragments/" + fragmentId + "/" + kind.segment() + "/";
    }

    private void requireOwnedKey(long userId, long fragmentId, MediaKind kind, String key) {
        String prefix = prefix(userId, fragmentId, kind);
        if (key == null || !key.startsWith(prefix) || !key.substring(prefix.length()).matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Object key does not match owner, fragment and media kind");
        }
    }

    private Digest digest(Path file, long limit) {
        try {
            if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            long initialSize = Files.size(file);
            if (initialSize > limit) throw new OssStorageFailure(FILE_TOO_LARGE, false);
            var sha = MessageDigest.getInstance("SHA-256");
            var md5 = MessageDigest.getInstance("MD5");
            long read = 0;
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) {
                    read += count;
                    if (read > limit) throw new OssStorageFailure(FILE_TOO_LARGE, false);
                    sha.update(buffer, 0, count); md5.update(buffer, 0, count);
                }
            }
            if (read == 0 || read != initialSize) throw new OssStorageFailure(LOCAL_FILE_ERROR, false);
            return new Digest(read, HexFormat.of().formatHex(sha.digest()), Base64.getEncoder().encodeToString(md5.digest()));
        } catch (IOException failure) { throw new OssStorageFailure(LOCAL_FILE_ERROR, false); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("Required digest unavailable"); }
    }

    private OssStorageFailure mapped(OSSException failure) {
        String code = failure.getErrorCode() == null ? "" : failure.getErrorCode();
        return switch (code) {
            case "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "SecurityTokenExpired", "InvalidSecurityToken" -> new OssStorageFailure(ACCESS_DENIED, false);
            case "NoSuchBucket", "NoSuchKey" -> new OssStorageFailure(NOT_FOUND, false);
            case "InvalidDigest", "BadDigest" -> new OssStorageFailure(INTEGRITY_ERROR, false);
            case "InternalError", "ServiceUnavailable", "SlowDown", "RequestTimeout" -> new OssStorageFailure(UNAVAILABLE, true);
            default -> new OssStorageFailure(PROVIDER_REJECTED, false);
        };
    }
    private record Digest(long size, String sha256, String md5) { }
}
