package com.fragpicker.knowledge.index;

public record IndexLease(long jobId, long fragmentId, long userId, long version, String owner, int attempt) { }
