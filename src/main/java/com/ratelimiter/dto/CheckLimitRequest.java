package com.ratelimiter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CheckLimitRequest {

    @NotBlank(message = "clientId is required")
    private String clientId;

    @Min(value = 1, message = "limit must be at least 1")
    private long limit = 100;

    @Min(value = 1, message = "windowSeconds must be at least 1")
    private long windowSeconds = 60;
}
