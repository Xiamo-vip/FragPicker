package com.fragpicker.ingestion.preview;

import com.fragpicker.common.api.ApiException;
import com.fragpicker.ingestion.ShareLinkResolver;
import com.fragpicker.integration.parsevideo.*;
import com.fragpicker.integration.media.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("database")
public class PreviewService {
    private final ShareLinkResolver links;
    private final ObjectProvider<PreviewParser> parsers;
    private final ObjectProvider<MediaResourceProbe> probes;
    private final PreviewLimiter limiter;
    public PreviewService(ShareLinkResolver links,ObjectProvider<PreviewParser> parsers,ObjectProvider<MediaResourceProbe> probes,PreviewLimiter limiter) {
        this.links=links;this.parsers=parsers;this.probes=probes;this.limiter=limiter;
    }
    public PreviewResponse preview(long owner,String share) {
        var source=links.resolve(share); var parser=parsers.getIfAvailable(); var probe=probes.getIfAvailable();
        if(parser==null || probe==null)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"PREVIEW_DISABLED","分享预览服务暂未启用");
        try(var permit=limiter.acquire(owner)) {
            var video=parser.parse(source.toASCIIString());
            String type=probe.check(video.videoUrl(),source);
            return new PreviewResponse(source.toASCIIString(),source.getHost(),display(video.title(),160,"视频资源"),display(video.authorName(),80,null),true,type);
        } catch(ParseVideoFailure failure){
            boolean unsupported=failure.code()==ParseVideoFailure.Code.INVALID_LINK || failure.code()==ParseVideoFailure.Code.UNSUPPORTED_CONTENT;
            throw new ApiException(unsupported?HttpStatus.UNPROCESSABLE_ENTITY:HttpStatus.BAD_GATEWAY,
                unsupported?"PREVIEW_UNSUPPORTED":"PREVIEW_UNAVAILABLE","暂时无法解析这条分享链接");
        } catch(MediaDownloadFailure failure){
            throw new ApiException(failure.retryable()?HttpStatus.GATEWAY_TIMEOUT:HttpStatus.UNPROCESSABLE_ENTITY,"PREVIEW_RESOURCE_UNAVAILABLE","视频资源暂时无法读取");
        } catch(ApiException failure){throw failure;}
        catch(Exception failure){throw new ApiException(HttpStatus.BAD_GATEWAY,"PREVIEW_UNAVAILABLE","分享资源暂时无法读取");}
    }
    private String display(String value,int maximum,String fallback) {
        if(value==null)return fallback;
        String text=value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").strip();
        if(text.isBlank())return fallback;
        return text.substring(0,text.offsetByCodePoints(0,Math.min(maximum,text.codePointCount(0,text.length()))));
    }
    public record PreviewResponse(String sourceUrl,String sourceHost,String title,String author,boolean resourceAvailable,String contentType) {}
}
