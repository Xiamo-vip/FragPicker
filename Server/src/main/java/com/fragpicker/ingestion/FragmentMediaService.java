package com.fragpicker.ingestion;

import com.fragpicker.common.api.ApiException;
import com.fragpicker.integration.oss.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("database")
public class FragmentMediaService {
    private final StoredMediaMapper media;
    private final ObjectProvider<OssMediaStorage> storage;
    private final OssProperties properties;
    public FragmentMediaService(StoredMediaMapper media, ObjectProvider<OssMediaStorage> storage, OssProperties properties) {
        this.media = media; this.storage = storage; this.properties = properties;
    }
    public FragmentMediaResponse get(long userId, long fragmentId, MediaKind kind) {
        // The composite owner FK ensures a stored checkpoint also belongs to an existing fragment.
        var saved = media.find(fragmentId, userId, kind);
        if (saved == null) throw notFound();
        var signer = storage.getIfAvailable();
        if (signer == null || !saved.bucket().equals(properties.bucket())) throw unavailable();
        try {
            var signed = signer.signedGet(userId, fragmentId, kind, saved.objectKey());
            return new FragmentMediaResponse(kind, signed.url(), signed.expiresAt(), saved.sizeBytes(), saved.contentType());
        } catch (OssStorageFailure failure) {
            if (failure.code() == OssStorageFailure.Code.NOT_FOUND) throw notFound();
            throw unavailable();
        } catch (IllegalArgumentException invalidCheckpoint) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_INVALID_CHECKPOINT", "媒体记录需要修复");
        }
    }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "未找到可用媒体"); }
    private ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE", "媒体服务暂不可用，请稍后重试"); }
}
