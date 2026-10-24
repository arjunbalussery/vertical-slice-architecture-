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
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
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
 * VERTICAL SLICE: Cancel an existing appointment (`POST /api/appointments/{appointmentId}/cancel`).
 */
@RestController
@Tag(name = "Appointments")
class CancelAppointmentSlice(
    private val appointments: AppointmentRepository,
    private val clock: Clock
) {
    data class Request(
        @field:NotBlank(message = "Cancellation reason is required")
        @field:Size(
            max = SchedulingPolicies.MAX_CANCELLATION_REASON_LENGTH,
            message = "Cancellation reason cannot exceed ${SchedulingPolicies.MAX_CANCELLATION_REASON_LENGTH} characters"
        )
        val reason: String?
    )

    data class Response(
        val id: UUID,
        val status: AppointmentStatus,
        val cancelledUtc: Instant,
        val cancellationReason: String
    )

    @PostMapping("/api/appointments/{appointmentId}/cancel")
    @Operation(summary = "Cancel an existing appointment")
    fun handleEndpoint(
        @PathVariable appointmentId: UUID,
        @Valid @RequestBody request: Request
    ): ResponseEntity<*> {
        return handle(appointmentId, request.reason!!).toResponseEntity()
    }

    @Transactional
    fun handle(appointmentId: UUID, reason: String): AppResult<Response> {
        val appointment = appointments.findById(appointmentId).orElse(null)
            ?: return AppResult.failure(
                AppError.NotFound("Appointment.NotFound", "Appointment with ID $appointmentId not found")
            )

        try {
            appointment.cancel(reason, clock.instant())
        } catch (ex: IllegalStateException) {
            return AppResult.failure(
                AppError.Validation("Appointment.CannotCancel", ex.message ?: "Cannot cancel appointment")
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

        return AppResult.success(
            Response(
                id = appointment.id,
                status = appointment.status,
                cancelledUtc = appointment.cancelledUtc!!,
                cancellationReason = appointment.cancellationReason!!
            )
        )
    }
}
