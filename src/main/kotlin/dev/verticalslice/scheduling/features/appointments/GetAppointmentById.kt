package dev.verticalslice.scheduling.features.appointments

import dev.verticalslice.scheduling.features.appointments.domain.Appointment
import dev.verticalslice.scheduling.features.appointments.domain.AppointmentRepository
import dev.verticalslice.scheduling.features.appointments.domain.AppointmentStatus
import dev.verticalslice.scheduling.shared.error.AppError
import dev.verticalslice.scheduling.shared.error.AppResult
import dev.verticalslice.scheduling.shared.error.toResponseEntity
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Read projection for Appointment queries, co-located inside the `appointments` feature package.
 */
data class AppointmentSummaryResponse(
    val id: UUID,
    val patientId: UUID,
    val patientName: String,
    val doctorId: UUID,
    val doctorName: String,
    val doctorSpecialty: String,
    val startUtc: Instant,
    val endUtc: Instant,
    val status: AppointmentStatus,
    val notes: String?,
    val completedUtc: Instant?,
    val cancelledUtc: Instant?,
    val cancellationReason: String?,
    val createdUtc: Instant,
    val lastModifiedUtc: Instant
) {
    companion object {
        fun from(appointment: Appointment): AppointmentSummaryResponse = AppointmentSummaryResponse(
            id = appointment.id,
            patientId = appointment.patientId,
            patientName = appointment.patient?.fullName ?: "Unknown Patient",
            doctorId = appointment.doctorId,
            doctorName = appointment.doctor?.fullName ?: "Unknown Doctor",
            doctorSpecialty = appointment.doctor?.specialty ?: "General",
            startUtc = appointment.startUtc,
            endUtc = appointment.endUtc,
            status = appointment.status,
            notes = appointment.notes,
            completedUtc = appointment.completedUtc,
            cancelledUtc = appointment.cancelledUtc,
            cancellationReason = appointment.cancellationReason,
            createdUtc = appointment.createdUtc,
            lastModifiedUtc = appointment.lastModifiedUtc
        )
    }
}

/**
 * VERTICAL SLICE: Get a single appointment by ID (`GET /api/appointments/{id}`).
 */
@RestController
@Tag(name = "Appointments")
class GetAppointmentByIdSlice(
    private val appointments: AppointmentRepository
) {
    @GetMapping("/api/appointments/{id}")
    @Operation(summary = "Get a single appointment by ID")
    fun handleEndpoint(@PathVariable id: UUID): ResponseEntity<*> {
        return handle(id).toResponseEntity()
    }

    @Transactional(readOnly = true)
    fun handle(id: UUID): AppResult<AppointmentSummaryResponse> {
        val appointment = appointments.findByIdWithDetails(id)
            ?: return AppResult.failure(
                AppError.NotFound("Appointment.NotFound", "Appointment with ID $id not found")
            )

        return AppResult.success(AppointmentSummaryResponse.from(appointment))
    }
}
