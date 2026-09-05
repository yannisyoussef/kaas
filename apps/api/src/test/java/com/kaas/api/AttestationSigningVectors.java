package com.kaas.api;

import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a published signing vector, from the module or the repository root. */
final class AttestationSigningVectors {

    private AttestationSigningVectors() {}

    static String document(String name) {
        Path fromModule = Path.of("..", "..", "packages", "api-contracts",
                "fixtures", "sandbox-security-attestation-signing", name);
        Path path = fromModule.toFile().isFile()
                ? fromModule
                : Path.of("packages", "api-contracts", "fixtures",
                        "sandbox-security-attestation-signing", name);
        try {
            return Files.readString(path);
        } catch (Exception missing) {
            throw new IllegalStateException("The signing vector " + name + " is missing", missing);
        }
    }
}
