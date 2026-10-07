package com.fragpicker.digest;

import java.time.*;

public record DigestChange(long userId, LocalDate businessDate, long version, long scheduledVersion, LocalDateTime dueAt) { }
