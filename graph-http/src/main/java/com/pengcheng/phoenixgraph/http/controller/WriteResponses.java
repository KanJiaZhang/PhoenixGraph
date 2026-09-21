package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.dto.WriteResult;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Maps a {@link WriteOutcome} to an HTTP response.
 */
final class WriteResponses {

    private WriteResponses() {
    }

    static ResponseEntity<WriteResult> of(Object id, WriteOutcome outcome) {
        HttpStatus status = switch (outcome) {
            case SUCCESS, SUCCESS_STALE -> HttpStatus.OK;
            case CONFLICT -> HttpStatus.CONFLICT;
            case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(new WriteResult(id, outcome.name()));
    }
}
