package com.example.ar

/**
 * State classification of a tracked planar surface throughout the validation pipeline.
 */
enum class SurfaceConfidence {
    CANDIDATE,       // Raw plane detected with valid orientation, accumulating tracking frames
    STABILIZING,     // Consecutive tracking maintained, checking spatial and geometry stability
    VALID,           // Passed all stability, orientation, area, and geometry checks: ready for placement
    TOO_SMALL,       // Filtered out: does not meet minimum area or extent thresholds
    DEGENERATE,      // Filtered out: extreme aspect ratio or irregular polygon geometry (clutter)
    UNSTABLE,        // Filtered out: center or extents jittering excessively
    LOST             // Tracking degraded, paused, or subsumed by parent plane
}

/**
 * State of the central targeting reticle relative to the environment and validated surfaces.
 */
enum class ReticleTargetState {
    SEARCHING,          // Reticle is pointing at empty space or untracked environment
    ANALYZING_SURFACE,  // Reticle is hovering over an active candidate/stabilizing surface
    VALID_SURFACE       // Reticle is directly intersecting a validated, placement-ready surface
}
