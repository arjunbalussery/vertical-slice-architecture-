package dev.verticalslice.scheduling.features.appointments

import dev.verticalslice.scheduling.features.appointments.domain.AppointmentRepository
import dev.verticalslice.scheduling.features.appointments.domain.AppointmentStatus
import dev.verticalslice.scheduling.features.appointments.domain.SchedulingPolicies
import dev.verticalslice.scheduling.shared.error.AppError
import dev.verticalslice.scheduling.shared.error.AppResult
import dev.verticalslice.scheduling.shared.error.toResponseEntity
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * VERTICAL SLICE: Complete an existing appointment (`POST /api/appointments/{appointmentId}/complete`).
 *
 * The route parameter `appointmentId` is the single source of truth, and the request body
 * only carries optional completion `notes`.
 */
@RestController
@Tag(name = "Appointments")
class CompleteAppointmentSlice(
    private val appointments: AppointmentRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock
) {
    data class Request(
        @field:Size(
            max = SchedulingPolicies.MAX_NOTES_LENGTH,
            message = "Notes cannot exceed ${SchedulingPolicies.MAX_NOTES_LENGTH} characters"
        )
        val notes: String? = null
    )

    data class Result(
        val id: UUID,
        val status: AppointmentStatus,
        val completedUtc: Instant,
        val notes: String?
    )

    @PostMapping("/api/appointments/{appointmentId}/complete")
    @Operation(summary = "Complete an existing appointment")
    fun handleEndpoint(
        @PathVariable appointmentId: UUID,
        @Valid @RequestBody(required = false) request: Request?
    ): ResponseEntity<*> {
        return handle(appointmentId, request?.notes).toResponseEntity()
    }

    @Transactional
    fun handle(appointmentId: UUID, notes: String?): AppResult<Result> {
        val appointment = appointments.findById(appointmentId).orElse(null)
            ?: return AppResult.failure(
                AppError.NotFound("Appointment.NotFound", "Appointment with ID $appointmentId not found")
            )

        val completedEvent = try {
            appointment.complete(notes, clock.instant())
        } catch (ex: IllegalStateException) {
            return AppResult.failure(
                AppError.Validation("Appointment.CannotComplete", ex.message ?: "Cannot complete appointment")
            )
        } catch (ex: IllegalArgumentException) {
            return AppResult.failure(
                AppError.Validation("Appointment.ValidationFailed", ex.message ?: "Validation failed")
            )
        }

        try {
            appointments.saveAndFlush(appointment)
        } catch (ex: OptimisticLockingFailureException) {
            return AppResult.failure(
                AppError.Conflict(
                    "Appointment.ConcurrencyConflict",
                    "The appointment was modified by another user. Please refresh and try again."
                )
            )
        }

        if (completedEvent != null) {
            events.publishEvent(completedEvent)
        }

        return AppResult.success(
            Result(
                id = appointment.id,
                status = appointment.status,
                completedUtc = appointment.completedUtc!!,
                notes = appointment.notes
            )
        )
    }
}
