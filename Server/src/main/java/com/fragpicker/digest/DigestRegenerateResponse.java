package com.fragpicker.digest;

import java.time.LocalDate;
public record DigestRegenerateResponse(LocalDate date,long revision,String status,boolean duplicate) { }
