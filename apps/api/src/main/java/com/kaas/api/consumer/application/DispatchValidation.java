package com.kaas.api.consumer.application;

import com.kaas.api.controlplane.domain.ExecutionDispatch;

/**
 * The outcome of deciding whether a delivered message can be believed at all.
 *
 * <p>This is the transport boundary: everything past it is a message whose bytes parse under the strict contract
 * and whose semantic digest re-derives to the value it carries. That does not make it authoritative — the
 * control plane still has to agree that the run it names exists and is claimable — only well formed.
 */
public sealed interface DispatchValidation {

    record Accepted(ExecutionDispatch dispatch) implements DispatchValidation {}

    /**
     * The message cannot be understood or trusted, and no redelivery will change that. The reason is a bounded
     * code, never the payload: echoing bytes the consumer just refused to parse into a log is how an untrusted
     * message reaches an operator's terminal.
     */
    record Rejected(String reason) implements DispatchValidation {}
}
