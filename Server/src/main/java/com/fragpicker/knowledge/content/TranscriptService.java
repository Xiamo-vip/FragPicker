package com.fragpicker.knowledge.content;

import com.fragpicker.common.api.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
@Profile("database")
public class TranscriptService {
    private final TranscriptMapper data;
    public TranscriptService(TranscriptMapper data) { this.data = data; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TranscriptResponse get(long user, long id, String kindText, String ordinalText, String offsetText, String limitText) {
        TranscriptResponse.Kind kind; int ordinal, offset, limit;
        try {
            kind = TranscriptResponse.Kind.valueOf(kindText); ordinal = Integer.parseInt(ordinalText); offset = Integer.parseInt(offsetText); limit = Integer.parseInt(limitText);
            if (ordinal < 0 || offset < 0 || offset > 16777215 || limit < 1 || limit > 20) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException invalid) { throw invalid(); }
        Boolean available = data.available(user, id);
        if (available == null) throw new ApiException(HttpStatus.NOT_FOUND, "FRAGMENT_NOT_FOUND", "未找到投喂记录");
        if (!available) return new TranscriptResponse(id, kind, false, List.of(), null);
        var result = new ArrayList<TranscriptResponse.Segment>(); boolean end = false;
        while (result.size() < limit) {
            var row = row(user, id, kind, ordinal, offset); if (row == null) { if (offset != 0) throw invalid(); end = true; break; }
            if ((row.ordinal() != ordinal && offset != 0) || offset > row.length() || (offset != 0 && offset == row.length())) throw invalid();
            if (row.ordinal() != ordinal) { ordinal = row.ordinal(); offset = 0; }
            result.add(new TranscriptResponse.Segment(row.ordinal(), offset, offset > 0, row.sentenceId(), row.speakerId(), row.speakerTruncated(), row.startMs(), row.endMs(), row.text()));
            int consumed = row.text().codePointCount(0, row.text().length()); offset += consumed;
            if (offset >= row.length()) {
                if (ordinal == Integer.MAX_VALUE) { end = true; break; }
                ordinal++; offset = 0;
            } else if (consumed == 0) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONTENT_INVALID", "资料内容暂时无法读取");
        }
        var next = !end && row(user, id, kind, ordinal, offset) != null ? new TranscriptResponse.Cursor(ordinal, offset) : null;
        return new TranscriptResponse(id, kind, true, result, next);
    }
    private TranscriptRow row(long user, long id, TranscriptResponse.Kind kind, int ordinal, int offset) {
        return kind == TranscriptResponse.Kind.SENTENCE ? data.sentence(user, id, ordinal, offset) : data.point(user, id, ordinal, offset);
    }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TRANSCRIPT", "请检查原文类型和分页位置"); }
}
