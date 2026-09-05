package com.kaas.egress;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * An authorizer whose answer a test controls, recording exactly what it was asked.
 *
 * <p>Used for the cases where the question is what the proxy asks and in what order — not how the answer is
 * transported. The wire format is proven separately against a real HTTP authorization service, because those
 * are different claims and a single fixture that covered both would prove neither cleanly.
 */
final class RecordingAuthorizer implements EgressAuthorizer {

    record Question(String token, String destination) {}

    private final List<Question> asked = new CopyOnWriteArrayList<>();

    private volatile BiFunction<String, CanonicalDestination, AuthorizationDecision> answer =
            (token, destination) -> AuthorizationDecision.granted();

    @Override
    public AuthorizationDecision authorize(String capabilityToken, CanonicalDestination destination) {
        asked.add(new Question(capabilityToken, destination.canonical()));
        return answer.apply(capabilityToken, destination);
    }

    List<Question> asked() {
        return List.copyOf(asked);
    }

    void answerWith(BiFunction<String, CanonicalDestination, AuthorizationDecision> answer) {
        this.answer = answer;
    }

    void allowOnly(String canonicalDestination) {
        answerWith((token, destination) -> destination.canonical().equals(canonicalDestination)
                ? AuthorizationDecision.granted()
                : AuthorizationDecision.denied(DenialReason.DESTINATION_NOT_ALLOWED));
    }

    void deny(DenialReason reason) {
        answerWith((token, destination) -> AuthorizationDecision.denied(reason));
    }
}
