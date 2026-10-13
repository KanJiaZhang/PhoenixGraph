package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.service.AuditLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The audit view: what this instance has been asked to do, most recent first.
 *
 * <p>The durable record is the {@code com.pengcheng.phoenixgraph.audit} logger (a deployment routes it to
 * wherever its audit trail has to live); this endpoint is the convenience view an operator can ask without
 * having the log files open. It needs an {@code admin} key (see {@link
 * com.pengcheng.phoenixgraph.http.service.ApiKeys#permissionFor}), and on an open instance it is open like
 * everything else.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final AuditLog audit;

    public AuditController(AuditLog audit) {
        this.audit = audit;
    }

    /** The last {@code limit} calls (default 50, at most 500). */
    @GetMapping("/recent")
    public Map<String, Object> recent(@RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return audit.view(Math.max(1, Math.min(limit, MAX_LIMIT)));
    }
}
