package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.Feature;
import com.kaas.api.controlplane.domain.FeatureRevision;

public record CreatedFeature(Feature feature, FeatureRevision initialRevision) {}
