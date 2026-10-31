package com.keyloop.scheduler.web.dto;

import java.util.List;

/**
 * The body of every collection response (api-conventions spec): an object rather than a bare array,
 * so pagination members can be added later without breaking clients.
 */
public record ItemsResponse<T>(List<T> items) {
}
