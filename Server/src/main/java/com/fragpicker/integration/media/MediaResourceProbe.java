package com.fragpicker.integration.media;

import com.fragpicker.integration.oss.MediaKind;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import java.net.URI;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.fragpicker.integration.media.MediaDownloadFailure.Code.*;

/** Read at most 32 application bytes; abort before closing so an ignored Range cannot drain a video. */
public class MediaResourceProbe implements AutoCloseable {
    private final CloseableHttpClient client;
    private final Duration timeout;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(task->{
        var thread=new Thread(task,"media-preview-deadline"); thread.setDaemon(true); return thread;
    });
    public MediaResourceProbe(CloseableHttpClient client,Duration timeout) { this.client=client; this.timeout=timeout; }
    public String check(URI resource,URI source) {
        PublicNetworkPolicy.validateUri(resource); PublicNetworkPolicy.validateUri(source);
        var current=new AtomicReference<HttpGet>(); var expired=new AtomicBoolean();
        var deadline=deadlines.schedule(()->{ expired.set(true); var active=current.get(); if(active!=null)active.abort(); },timeout.toMillis(),TimeUnit.MILLISECONDS);
        try {
            URI target=resource;
            for(int hop=0;hop<=3;hop++) {
                PublicNetworkPolicy.validateUri(target);
                var request=new HttpGet(target); current.set(request);
                request.setHeader("Range","bytes=0-31"); request.setHeader("Accept-Encoding","identity");
                request.setHeader("Referer",source.getScheme()+"://"+source.getRawAuthority()+"/");
                if(expired.get())throw new MediaDownloadFailure(TIMEOUT,true);
                try(var response=client.execute(request)) {
                    try {
                    int status=response.getStatusLine().getStatusCode();
                    if(Set.of(301,302,303,307,308).contains(status)) {
                        var location=response.getFirstHeader("Location");
                        if(location==null || hop==3)throw new MediaDownloadFailure(REDIRECT_LIMIT,false);
                        try { target=target.resolve(location.getValue()); }
                        catch(IllegalArgumentException invalid){throw new MediaDownloadFailure(TARGET_REJECTED,false);}
                        continue;
                    }
                    if(status!=200 && status!=206)throw new MediaDownloadFailure(status==401 || status==403?SOURCE_EXPIRED:SOURCE_REJECTED,false);
                    if(status==206) {
                        var range=response.getFirstHeader("Content-Range");
                        if(range==null || !range.getValue().matches("bytes 0-[0-9]+/[0-9*]+"))throw new MediaDownloadFailure(UNSUPPORTED_CONTENT,false);
                    }
                    var entity=response.getEntity(); if(entity==null)throw new MediaDownloadFailure(UNSUPPORTED_CONTENT,false);
                    byte[] head=entity.getContent().readNBytes(32);
                    if(expired.get())throw new MediaDownloadFailure(TIMEOUT,true);
                    return MediaSniffer.detect(head,MediaKind.VIDEO);
                    } finally { request.abort(); }
                } finally { request.abort(); current.compareAndSet(request,null); }
            }
            throw new MediaDownloadFailure(REDIRECT_LIMIT,false);
        } catch(MediaDownloadFailure failure){throw failure;}
        catch(IOException failure){
            Throwable cause=failure;
            while(cause!=null){
                if(cause instanceof PublicNetworkPolicy.RejectedAddress)throw new MediaDownloadFailure(TARGET_REJECTED,false);
                if(cause instanceof java.net.SocketTimeoutException || cause instanceof org.apache.http.conn.ConnectTimeoutException)throw new MediaDownloadFailure(TIMEOUT,true);
                cause=cause.getCause();
            }
            throw new MediaDownloadFailure(expired.get()?TIMEOUT:NETWORK_ERROR,true);
        } finally { deadline.cancel(false); var active=current.getAndSet(null); if(active!=null)active.abort(); }
    }
    @Override public void close() throws IOException { deadlines.shutdownNow(); client.close(); }
}
