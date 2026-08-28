package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.RunProfile;
import com.kaas.api.controlplane.domain.RunProfileRevision;

public record CreatedRunProfile(RunProfile runProfile, RunProfileRevision initialRevision) {}
