package com.kaas.api.consumer.application;

import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.controlplane.domain.ExecutionDispatchPolicy;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides whether a delivered message can be believed as a dispatch at all.
 *
 * <p>The rule this enforces is that the consumer validates the exact bytes that were published. The relay
 * already shipped a defect of the opposite shape — verifying a reduced projection while publishing different raw
 * bytes — so unknown properties are a hard failure here rather than something Jackson quietly discards. Jackson
 * defaults {@code FAIL_ON_UNKNOWN_PROPERTIES} to false, which would let an attacker append fields the digest does
 * not cover and that a future, more trusting reader might act on.
 *
 * <p>The mapper is private for the same reason the scheduler's is: this is contract validation, and an
 * HTTP-shaping {@code spring.jackson.*} property must never be able to loosen it.
 */
@Component
public class DispatchContractValidator {
    private static final String SUPPORTED_SCHEMA_VERSION = "1.0";
    private static final String SUPPORTED_MESSAGE_TYPE = "EXECUTION_DISPATCH";

    /** Generous next to a real dispatch and far below anything that could exhaust a consumer thread's memory. */
    static final int MAX_BODY_BYTES = 64 * 1024;

    private static final ObjectMapper DISPATCH_MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public DispatchValidation validate(DispatchMessage message) {
        // Size is checked before parsing, so an oversized body is refused without ever being materialised as a
        // document. A parser is the wrong place to discover that a message is too big.
        if (message.size() > MAX_BODY_BYTES) {
            return new DispatchValidation.Rejected("BODY_TOO_LARGE");
        }
        if (message.size() == 0) {
            return new DispatchValidation.Rejected("EMPTY_BODY");
        }
        // Transport headers are a cheap early-out and nothing more. They can only ever cause a rejection: a
        // header that agrees with the contract buys the message nothing, because the body is checked against the
        // same values below and the digest covers the body alone. Absence is tolerated for the same reason —
        // refusing on a missing header would be a rejection about the envelope rather than about the message.
        if (message.messageType() != null && !SUPPORTED_MESSAGE_TYPE.equals(message.messageType())) {
            return new DispatchValidation.Rejected("UNSUPPORTED_MESSAGE_TYPE");
        }
        if (message.schemaVersion() != null && !SUPPORTED_SCHEMA_VERSION.equals(message.schemaVersion())) {
            return new DispatchValidation.Rejected("UNSUPPORTED_SCHEMA_VERSION");
        }
        String json;
        try {
            json = strictUtf8(message.body());
        } catch (CharacterCodingException malformed) {
            return new DispatchValidation.Rejected("MALFORMED_ENCODING");
        }
        ExecutionDispatch dispatch;
        try {
            dispatch = DISPATCH_MAPPER.readValue(json, ExecutionDispatch.class);
        } catch (UnrecognizedPropertyException extraneous) {
            // Its own reason rather than an alias of MALFORMED_PAYLOAD. A shared code cannot distinguish "this is
            // not JSON" from "this carries a field the contract does not declare", and only the second says
            // somebody appended something the digest does not cover.
            return new DispatchValidation.Rejected("UNKNOWN_PROPERTY");
        } catch (RuntimeException malformed) {
            // Deliberately not logged with the payload attached: the exception message can quote the bytes that
            // were just refused, and an untrusted message must not reach an operator's terminal that way.
            return new DispatchValidation.Rejected("MALFORMED_PAYLOAD");
        }
        // The digest binds every semantic field, so re-deriving it proves identity, tenancy, version, attempt,
        // snapshot binding, and deadline in one check — and proves them about these bytes, not about a header.
        if (!ExecutionDispatchPolicy.digest(dispatch).equals(dispatch.payloadDigest())) {
            return new DispatchValidation.Rejected("DIGEST_MISMATCH");
        }
        if (!SUPPORTED_MESSAGE_TYPE.equals(dispatch.messageType())
                || !SUPPORTED_SCHEMA_VERSION.equals(dispatch.schemaVersion())) {
            return new DispatchValidation.Rejected("CONTRACT_MISMATCH");
        }
        // A transport identity that disagrees with the body is a reason to disbelieve the message, never a
        // reason to prefer the header. The body is what the digest covers.
        UUID transportId = message.transportMessageId();
        if (transportId != null && !transportId.equals(dispatch.messageId())) {
            return new DispatchValidation.Rejected("TRANSPORT_IDENTITY_MISMATCH");
        }
        return new DispatchValidation.Accepted(dispatch);
    }

    /** Rejects malformed UTF-8 rather than substituting replacement characters into a digest-bound document. */
    private static String strictUtf8(byte[] body) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString();
    }
}
