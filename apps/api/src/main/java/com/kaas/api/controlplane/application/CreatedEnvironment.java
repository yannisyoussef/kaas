package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.Environment;
import com.kaas.api.controlplane.domain.EnvironmentRevision;

public record CreatedEnvironment(Environment environment, EnvironmentRevision initialRevision) {}
