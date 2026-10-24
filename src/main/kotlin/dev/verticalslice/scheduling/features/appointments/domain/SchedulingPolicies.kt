package dev.verticalslice.scheduling.features.appointments.domain

/**
 * Business policies and constraints for appointment scheduling.
 * Co-located directly inside the `appointments` feature package rather than a detached horizontal layer.
 */
object SchedulingPolicies {
    const val MINIMUM_APPOINTMENT_DURATION_MINUTES = 10L
    const val MAXIMUM_APPOINTMENT_DURATION_HOURS = 8L
    const val MINIMUM_BOOKING_ADVANCE_MINUTES = 15L
    const val MAX_NOTES_LENGTH = 1024
    const val MAX_CANCELLATION_REASON_LENGTH = 512
}
