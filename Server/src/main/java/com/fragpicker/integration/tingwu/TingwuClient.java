package com.fragpicker.integration.tingwu;

import com.aliyun.tea.TeaException;
import com.aliyun.teautil.models.RuntimeOptions;
import com.aliyun.tingwu20230930.Client;
import com.aliyun.tingwu20230930.models.CreateTaskRequest;
import com.aliyun.tingwu20230930.models.CreateTaskRequest.*;
import java.net.URI;
import java.util.List;
import java.util.Map;
import static com.fragpicker.integration.tingwu.TingwuFailure.Code.*;

/** Internal cloud adapter. TaskKey is a label, not an idempotency guarantee. */
public class TingwuClient {
    private final Client sdk;
    private final TingwuProperties properties;
    public TingwuClient(Client sdk, TingwuProperties properties) { properties.validate(); this.sdk = sdk; this.properties = properties; }

    public TingwuTask create(URI fileUrl, String taskKey) {
        if (taskKey == null || !taskKey.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid task key");
        URI source = TingwuUrls.requirePublicOss(fileUrl == null ? "" : fileUrl.toASCIIString());
        var parameters = new CreateTaskRequestParameters()
                .setTranscription(new CreateTaskRequestParametersTranscription().setDiarizationEnabled(true))
                .setSummarizationEnabled(true)
                .setSummarization(new CreateTaskRequestParametersSummarization().setTypes(List.of("Paragraph")))
                .setMeetingAssistanceEnabled(true)
                .setMeetingAssistance(new CreateTaskRequestParametersMeetingAssistance().setTypes(List.of("KeyInformation")));
        var request = new CreateTaskRequest().setType("offline").setAppKey(properties.appKey())
                .setInput(new CreateTaskRequestInput().setFileUrl(source.toASCIIString()).setTaskKey(taskKey)
                        .setSourceLanguage(properties.sourceLanguage()).setProgressiveCallbacksEnabled(false))
                .setParameters(parameters);
        try {
            var body = sdk.createTaskWithOptions(request, Map.of(), runtime()).getBody();
            if (body == null || !"0".equals(body.getCode()) || body.getData() == null) throw new TingwuFailure(SUBMISSION_UNCERTAIN, false);
            var data = body.getData();
            if (!validId(data.getTaskId()) || (data.getTaskKey() != null && !taskKey.equals(data.getTaskKey()))) {
                throw new TingwuFailure(SUBMISSION_UNCERTAIN, false);
            }
            return new TingwuTask(data.getTaskId(), taskKey, status(data.getTaskStatus(), true), null, null, null, null);
        } catch (TingwuFailure failure) { throw failure; }
        catch (Exception failure) { throw mapped(failure, true); }
    }

    public TingwuTask get(String taskId) {
        if (!validId(taskId)) throw new IllegalArgumentException("Invalid task id");
        try {
            var body = sdk.getTaskInfoWithOptions(taskId, Map.of(), runtime()).getBody();
            if (body == null || !"0".equals(body.getCode()) || body.getData() == null) throw new TingwuFailure(INVALID_RESPONSE, false);
            var data = body.getData();
            if (!taskId.equals(data.getTaskId())) throw new TingwuFailure(INVALID_RESPONSE, false);
            var state = status(data.getTaskStatus(), false);
            var failure = state == TingwuTask.Status.FAILED || state == TingwuTask.Status.INVALID
                    ? category(data.getErrorCode()) : null;
            var result = data.getResult();
            return new TingwuTask(taskId, data.getTaskKey(), state, failure,
                    result == null ? null : resultUrl(result.getTranscription()),
                    result == null ? null : resultUrl(result.getSummarization()),
                    result == null ? null : resultUrl(result.getMeetingAssistance()));
        } catch (TingwuFailure failure) { throw failure; }
        catch (Exception failure) { throw mapped(failure, false); }
    }
    private RuntimeOptions runtime() {
        return new RuntimeOptions().setAutoretry(false).setMaxAttempts(1).setIgnoreSSL(false)
                .setConnectTimeout((int) properties.connectTimeout().toMillis())
                .setReadTimeout((int) properties.readTimeout().toMillis());
    }
    private URI resultUrl(String value) { return value == null || value.isBlank() ? null : TingwuUrls.requirePublicOss(value); }
    private boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,128}"); }
    private TingwuTask.Status status(String value, boolean creating) {
        try { return TingwuTask.Status.valueOf(value); }
        catch (RuntimeException invalid) { throw new TingwuFailure(creating ? SUBMISSION_UNCERTAIN : INVALID_RESPONSE, false); }
    }
    private TingwuTask.FailureCategory category(String code) {
        if ("TSC.AudioFileLink".equals(code)) return TingwuTask.FailureCategory.SOURCE_INVALID;
        if ("TSC.AudioFormat".equals(code)) return TingwuTask.FailureCategory.UNSUPPORTED_MEDIA;
        return TingwuTask.FailureCategory.OTHER;
    }
    private TingwuFailure mapped(Exception error, boolean creating) {
        if (error instanceof TeaException tea) {
            int status = tea.getStatusCode() == null ? 0 : tea.getStatusCode();
            String code = tea.getCode() == null ? "" : tea.getCode();
            if (status == 401 || status == 403 || code.startsWith("Forbidden") || code.contains("AccessKey") || code.contains("Signature")) {
                return new TingwuFailure(ACCESS_DENIED, false);
            }
            if (status == 429 || code.startsWith("Throttling")) return new TingwuFailure(THROTTLED, true);
            if (status == 404) return new TingwuFailure(TASK_NOT_FOUND, false);
            if (status == 400 || code.startsWith("InvalidParameter")) return new TingwuFailure(INVALID_REQUEST, false);
        }
        // A timeout/5xx can happen after a billable task was accepted. Do not blindly recreate it.
        return new TingwuFailure(creating ? SUBMISSION_UNCERTAIN : UNAVAILABLE, !creating);
    }
}
