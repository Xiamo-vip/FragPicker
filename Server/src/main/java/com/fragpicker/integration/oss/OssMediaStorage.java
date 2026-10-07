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
        return sign(key, ttl);
    }

    /** Internal Tingwu input only. Never expose this longer-lived capability as a playback URL. */
    public SignedMediaUrl signedGetForTranscription(long userId, long fragmentId, String key, Duration ttl) {
        requireOwnedKey(userId, fragmentId, MediaKind.VIDEO, key);
        if (ttl == null || ttl.compareTo(Duration.ofHours(3)) < 0 || ttl.compareTo(Duration.ofHours(12)) > 0) {
            throw new IllegalArgumentException("Transcription URL TTL must be 3..12 hours");
        }
        return sign(key, ttl);
    }

    private SignedMediaUrl sign(String key, Duration ttl) {
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

    /** Internal durable deletion only; bucket was copied from owned records/config, never from HTTP input. */
    public boolean deleteOwnedPage(String bucket,long userId,long fragmentId,java.util.function.BooleanSupplier renew) {
        if(bucket==null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))throw new IllegalArgumentException("Invalid cleanup bucket");
        prefix(userId,fragmentId,MediaKind.VIDEO);
        String scope="users/"+userId+"/fragments/"+fragmentId+"/";
        try {
            if(!renew.getAsBoolean())return false;
            var versioning=client.getBucketVersioning(bucket);
            if(versioning==null)throw new OssStorageFailure(PROVIDER_REJECTED,true);
            if(BucketVersioningConfiguration.ENABLED.equals(versioning.getStatus()) || BucketVersioningConfiguration.SUSPENDED.equals(versioning.getStatus()))
                return deleteOwnedVersionPage(bucket,scope,renew);
            if(versioning.getStatus()!=null && !BucketVersioningConfiguration.OFF.equals(versioning.getStatus()))throw new OssStorageFailure(PROVIDER_REJECTED,false);
            if(!renew.getAsBoolean())return false;
            var request=new ListObjectsV2Request(bucket).withPrefix(scope).withMaxKeys(100);
            var page=client.listObjectsV2(request);
            if(page==null || !bucket.equals(page.getBucketName()) || !scope.equals(page.getPrefix()))throw new OssStorageFailure(PROVIDER_REJECTED,false);
            var keys=page.getObjectSummaries().stream().map(OSSObjectSummary::getKey).toList();
            // Validate the entire page before issuing a delete, even if the provider response is malformed.
            for(String key:keys) {
                if(key==null || !key.startsWith(scope) || !key.substring(scope.length()).matches("(video|cover)/[a-f0-9]{64}"))
                    throw new OssStorageFailure(PROVIDER_REJECTED,false);
            }
            if(keys.isEmpty())return !page.isTruncated();
            if(keys.size()>100 || keys.stream().distinct().count()!=keys.size())throw new OssStorageFailure(PROVIDER_REJECTED,false);
            if(!renew.getAsBoolean())return false;
            var result=client.deleteObjects(new DeleteObjectsRequest(bucket).withKeys(keys).withQuiet(false));
            if(result==null || result.getDeletedObjects()==null || !new HashSet<>(result.getDeletedObjects()).equals(new HashSet<>(keys)))
                throw new OssStorageFailure(PROVIDER_REJECTED,true);
            // Confirm with a fresh list on the next bounded pass; tokens can move while deleting keys.
            return false;
        } catch(OSSException error) {throw mapped(error);}
        catch(ClientException error) {throw new OssStorageFailure(UNAVAILABLE,true);}
    }

    private record ObjectVersion(String key,String version) { }
    private boolean deleteOwnedVersionPage(String bucket,String scope,java.util.function.BooleanSupplier renew) {
        if(!renew.getAsBoolean())return false;
        var page=client.listVersions(new ListVersionsRequest().withBucketName(bucket).withPrefix(scope).withMaxResults(100));
        if(page==null || !bucket.equals(page.getBucketName()) || !scope.equals(page.getPrefix()))throw new OssStorageFailure(PROVIDER_REJECTED,false);
        var keys=new ArrayList<DeleteVersionsRequest.KeyVersion>();var expected=new HashSet<ObjectVersion>();
        for(var object:page.getVersionSummaries()) {
            var key=object.getKey();var version=object.getVersionId();
            if(key==null || !key.startsWith(scope) || !key.substring(scope.length()).matches("(video|cover)/[a-f0-9]{64}")
                || version==null || version.isBlank() || version.length()>1024 || version.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.isWhitespace(cp)))
                throw new OssStorageFailure(PROVIDER_REJECTED,false);
            if(!expected.add(new ObjectVersion(key,version)))throw new OssStorageFailure(PROVIDER_REJECTED,false);
            keys.add(new DeleteVersionsRequest.KeyVersion(key,version));
        }
        if(keys.isEmpty())return !page.isTruncated();
        if(keys.size()>100)throw new OssStorageFailure(PROVIDER_REJECTED,false);
        if(!renew.getAsBoolean())return false;
        var result=client.deleteVersions(new DeleteVersionsRequest(bucket).withKeys(keys).withQuiet(false));
        if(result==null || result.getDeletedVersions()==null)throw new OssStorageFailure(PROVIDER_REJECTED,true);
        var acknowledged=new HashSet<ObjectVersion>();
        for(var deleted:result.getDeletedVersions())acknowledged.add(new ObjectVersion(deleted.getKey(),deleted.getVersionId()!=null?deleted.getVersionId():deleted.getDeleteMarkerVersionId()));
        if(!acknowledged.equals(expected))throw new OssStorageFailure(PROVIDER_REJECTED,true);
        return false;
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
