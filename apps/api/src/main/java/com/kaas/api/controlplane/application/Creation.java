package com.kaas.api.controlplane.application;

public record Creation<T>(T value, String location, boolean replayed) {}
