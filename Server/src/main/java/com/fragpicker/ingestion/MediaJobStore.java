package com.fragpicker.ingestion;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fragpicker.integration.oss.MediaKind;
import com.fragpicker.integration.oss.StoredMedia;
import com.fragpicker.integration.parsevideo.ParsedVideo;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

@Service
@Profile("database")
public class MediaJobStore {
    private final MediaJobMapper jobs;
    private final FragmentRecordMapper fragments;
    private final VideoMetadataMapper metadata;
    private final StoredMediaMapper media;
    private final MediaWorkerProperties properties;
    private final com.fragpicker.ingestion.deletion.DeletionMapper deletions;

    public MediaJobStore(MediaJobMapper jobs, FragmentRecordMapper fragments, VideoMetadataMapper metadata,
                         StoredMediaMapper media, MediaWorkerProperties properties, com.fragpicker.ingestion.deletion.DeletionMapper deletions) {
        properties.validate();
        this.jobs = jobs; this.fragments = fragments; this.metadata = metadata; this.media = media; this.properties = properties;
        this.deletions=deletions;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<MediaLease> claim() {
        var job = jobs.lockNext();
        if (job == null) return Optional.empty();
        if (job.getMediaAttemptCount() >= properties.maxAttempts()) {
            jobs.exhaust(job.getId()); updateFragment(job.getFragmentId(), job.getUserId(), "FAILED");
            return Optional.empty();
        }
        String owner = UUID.randomUUID().toString();
        if (jobs.claim(job.getId(), owner, properties.leaseDuration().toSeconds()) != 1) {
            throw new IllegalStateException("Could not claim locked media job");
        }
        updateFragment(job.getFragmentId(), job.getUserId(), "MEDIA_SAVING");
        var fragment = fragments.selectById(job.getFragmentId());
        return Optional.of(new MediaLease(job.getId(), job.getFragmentId(), job.getUserId(), job.getVersion() + 1,
                owner, job.getMediaAttemptCount() + 1, fragment.getSourceUrl()));
    }

    public VideoMetadata metadata(MediaLease lease) {
        var result = metadata.find(lease.fragmentId(), lease.userId());
        if (result == null) throw new IllegalStateException("Missing parsed metadata");
        return result;
    }
    public boolean isStored(MediaLease lease, MediaKind kind) { return media.find(lease.fragmentId(), lease.userId(), kind) != null; }

    @Transactional
    public boolean checkpoint(MediaLease lease, MediaKind kind, StoredMedia uploaded) {
        var record = StoredMediaRecord.from(lease, kind, uploaded);
        if (jobs.lockValid(lease) == null) {
            // The object may have arrived after deletion. Re-open its owned prefix cleanup, never delete shared live keys here.
            deletions.rescanLateUpload(lease.userId(),lease.fragmentId());return false;
        }
        var previous = media.find(lease.fragmentId(), lease.userId(), kind);
        if (previous != null) {
            if (!previous.equals(record)) throw new IllegalStateException("Media checkpoint already contains different content");
            return true;
        }
        if (media.insert(record) != 1) throw new IllegalStateException("Missing uploaded media checkpoint");
        return true;
    }

    @Transactional
    public boolean refreshMetadata(MediaLease lease, ParsedVideo video) {
        if (jobs.lockValid(lease) == null) return false;
        if (metadata.replace(new VideoMetadata(lease.fragmentId(), lease.userId(), video.title(), video.videoUrl().toASCIIString(),
                video.coverUrl() == null ? null : video.coverUrl().toASCIIString(), video.authorName(), video.authorUid(),
                video.authorAvatar() == null ? null : video.authorAvatar().toASCIIString())) != 1) {
            throw new IllegalStateException("Missing parsed metadata for refresh");
        }
        return true;
    }

    @Transactional
    public boolean complete(MediaLease lease) {
        if (jobs.lockValid(lease) == null) return false;
        var parsed = metadata(lease);
        if (!isStored(lease, MediaKind.VIDEO) || (parsed.coverUrl() != null && !isStored(lease, MediaKind.COVER))) {
            throw new IllegalStateException("Required media has not been saved");
        }
        if (jobs.finish(lease, "TRANSCRIPTION_PENDING", null, 0) != 1) return false;
        updateFragment(lease.fragmentId(), lease.userId(), "TRANSCRIPTION_PENDING");
        return true;
    }

    @Transactional
    public boolean fail(MediaLease lease, String code, boolean retryable) {
        if (code == null || !code.matches("[A-Z_]{1,64}")) throw new IllegalArgumentException("Invalid stable error code");
        String stage = retryable && lease.attemptCount() < properties.maxAttempts() ? "MEDIA_PENDING" : "FAILED";
        if (jobs.finish(lease, stage, code, properties.retryDelaySeconds(lease.attemptCount())) != 1) return false;
        updateFragment(lease.fragmentId(), lease.userId(), stage);
        return true;
    }
    private void updateFragment(long id, long userId, String stage) {
        int changed = fragments.update(null, Wrappers.<FragmentRecord>lambdaUpdate().eq(FragmentRecord::getId, id)
                .eq(FragmentRecord::getUserId, userId).set(FragmentRecord::getStatus, stage));
        if (changed != 1) throw new IllegalStateException("Missing fragment for durable media job");
    }
}
