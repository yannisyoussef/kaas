package com.kaas.api.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Timers live here rather than in the control plane so that the architecture rule forbidding schedulers inside
 * {@code ..controlplane..} keeps its meaning: the domain and its use cases stay free of wall-clock triggers.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class BackgroundSchedulingConfiguration {}
