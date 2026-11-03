package com.keyloop.scheduler.web.dto;

import java.util.List;

public record ItemsResponse<T>(List<T> items) {
}
