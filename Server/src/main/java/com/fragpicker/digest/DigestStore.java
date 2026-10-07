package com.fragpicker.digest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fragpicker.knowledge.EnrichmentResult;
import com.fragpicker.knowledge.EnrichmentResult.Category;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
@Profile("database")
public class DigestStore {
    private final DigestMapper jobs;
    private final DigestJobProperties properties;
    private final ObjectMapper json;
    public DigestStore(DigestMapper jobs, DigestJobProperties properties, ObjectMapper json) {
        properties.validate(); this.jobs=jobs; this.properties=properties;
        this.json=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    @Transactional
    public long enqueue(long owner, LocalDate date, LocalDateTime due, boolean rebuild) {
        if (owner < 1 || date == null || date.getYear() < 1000 || date.getYear() > 9999 || due == null) throw new IllegalArgumentException("Invalid digest day");
        if (jobs.lockUser(owner) == null) throw new IllegalArgumentException("Missing digest owner");
        var row=jobs.lockDay(owner,date);
        if (row == null) { jobs.insert(owner,date,due); return jobs.lockDay(owner,date).id(); }
        if (rebuild) jobs.queueRevision(row.id(),due);
        return row.id();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Optional<DigestLease> claim() {
        var row=jobs.lockNext(); if (row == null) return Optional.empty();
        if (row.inFlightHash() != null) { jobs.fail(row.id(),"DIGEST_AI_UNCONFIRMED"); return Optional.empty(); }
        boolean resume="RUNNING".equals(row.status());
        long revision=resume ? row.workingRevision() : row.requestedRevision();
        String token=UUID.randomUUID().toString();
        if (jobs.claim(row.id(),revision,token,properties.leaseDuration().toSeconds(),resume) != 1) throw new IllegalStateException("Missing digest job");
        return Optional.of(new DigestLease(row.id(),row.userId(),row.businessDate(),revision,token));
    }
    @Transactional
    public boolean renew(DigestLease lease) { return jobs.lockValid(lease) != null && jobs.renew(lease.id(),properties.leaseDuration().toSeconds()) == 1; }
    /** A consistent source snapshot is committed before any external model call. */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public void snapshot(DigestLease lease) {
        var row=valid(lease); if (row.workingSourceHash() != null) return;
        try {
            var hash=MessageDigest.getInstance("SHA-256"); Long before=null; long count=0;
            while (true) {
                var page=jobs.readySources(lease.userId(),lease.date(),before); if (page.isEmpty()) break;
                for (var raw:page) {
                    var source=decodeSource(raw); String encoded=encode(source);
                    jobs.source(lease,source.fragmentId(),encoded);
                    hash.update(encoded.getBytes(StandardCharsets.UTF_8)); hash.update((byte)'\n'); count++;
                }
                before=page.getLast().fragmentId();
            }
            jobs.snapshot(lease.id(),HexFormat.of().formatHex(hash.digest()),count);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Transactional(readOnly=true)
    public List<DigestSource> sources(DigestLease lease, Long before) {
        return jobs.sources(lease,before).stream().map(value -> decode(value,DigestSource.class)).toList();
    }
    @Transactional
    public Optional<DigestPiece> cached(DigestLease lease, String hash) {
        hash(hash); var row=valid(lease); String saved=jobs.cached(lease,hash); if (saved == null) return Optional.empty();
        var piece=decode(saved,DigestPiece.class); validatePiece(lease,row,piece); return Optional.of(piece);
    }
    @Transactional
    public boolean beginCall(DigestLease lease, String hash) {
        hash(hash); var row=jobs.lockValid(lease); if (row == null || row.workingSourceHash() == null) return false;
        jobs.renew(lease.id(),properties.leaseDuration().toSeconds()); return jobs.beginCall(lease.id(),hash) == 1;
    }
    @Transactional
    public boolean checkpoint(DigestLease lease, String hash, DigestPiece piece) {
        hash(hash); var row=jobs.lockValid(lease); if (row == null) return false;
        if (!Objects.equals(row.inFlightHash(),hash)) throw new IllegalStateException("Digest call does not match checkpoint");
        validatePiece(lease,row,piece); jobs.checkpoint(lease,hash,encode(piece)); jobs.clearFlight(lease.id());
        jobs.renew(lease.id(),properties.leaseDuration().toSeconds()); return true;
    }
    @Transactional
    public boolean complete(DigestLease lease, DigestPiece piece) {
        var row=jobs.lockValid(lease); if (row == null) return false;
        validatePiece(lease,row,piece);
        if (row.inFlightHash() != null || piece.sourceCount() != row.workingSourceCount() || jobs.sourceCount(lease) != row.workingSourceCount())
            throw new IllegalStateException("Incomplete digest snapshot");
        return jobs.complete(lease.id(),encode(piece),piece.modelCalls()) == 1;
    }
    @Transactional
    public boolean fail(DigestLease lease, String error) {
        if (error == null || !error.matches("[A-Z_]{1,64}")) throw new IllegalArgumentException("Invalid digest error");
        return jobs.lockValid(lease) != null && jobs.fail(lease.id(),error) == 1;
    }
    private DigestJob valid(DigestLease lease) {
        var row=jobs.lockValid(lease); if (row == null) throw new IllegalStateException("Digest lease no longer valid"); return row;
    }
    private void validatePiece(DigestLease lease, DigestJob row, DigestPiece piece) {
        if (row.workingSourceHash() == null || piece == null || piece.userId() != lease.userId() || !lease.date().equals(piece.date()) || piece.sourceCount() > row.workingSourceCount())
            throw new IllegalArgumentException("Invalid digest checkpoint scope");
        var ids=piece.points().stream().flatMap(point -> point.sourceIds().stream()).distinct().toList();
        if (!ids.isEmpty() && jobs.ownedReferences(lease,ids) != ids.size()) throw new IllegalArgumentException("Unowned digest reference");
    }
    private DigestSource decodeSource(DigestSourceRow row) {
        var points=readList(row.points(),new TypeReference<List<String>>() {});
        var categories=readList(row.categories(),new TypeReference<List<Category>>() {});
        var validated=new EnrichmentResult(row.summary(),points,categories);
        var keywords=readList(row.keywords(),new TypeReference<List<String>>() {});
        if (keywords.size() > 100) throw new IllegalArgumentException("Too many digest source keywords");
        keywords.forEach(word -> DigestText.require(word,100));
        return new DigestSource(row.fragmentId(),row.userId(),row.date(),row.title(),row.author(),validated.summary(),validated.points(),validated.categories(),keywords);
    }
    private <T> T readList(String value, TypeReference<T> type) {
        try { if (value == null || value.length() > 32768) throw new IllegalArgumentException("Invalid digest source JSON"); return json.readValue(value,type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid digest source JSON"); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { if (value.length() > 32768) throw new IllegalArgumentException("Invalid stored digest JSON"); return json.readValue(value,type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid stored digest JSON"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("Cannot encode digest JSON"); }
    }
    private static void hash(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid digest hash"); }
}
