package dev.qb2.perception;

import dev.qb2.companion.movement.Point;

public record Observation(
    String key,
    String registryId,
    String name,
    String state,
    Point position,
    double salience,
    long tick) {}
