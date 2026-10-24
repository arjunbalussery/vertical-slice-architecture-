package dev.verticalslice.scheduling.features.appointments

import dev.verticalslice.scheduling.features.appointments.domain.Appointment
import dev.verticalslice.scheduling.features.appointments.domain.AppointmentRepository
import dev.verticalslice.scheduling.features.appointments.domain.DoctorRepository
import dev.verticalslice.scheduling.features.appointments.domain.PatientRepository
import dev.verticalslice.scheduling.features.appointments.domain.SchedulingPolicies
import dev.verticalslice.scheduling.shared.error.AppError
import dev.verticalslice.scheduling.shared.error.AppResult
import dev.verticalslice.scheduling.shared.error.toResponseEntity
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * VERTICAL SLICE: Book a new appointment (`POST /api/appointments`).
 *
 * Everything required to book an appointment—HTTP route, request/response models,
 * validation, business orchestration, persistence, and event publication—lives in this single file.
 *
 * Key architectural transformations from Clean Architecture:
 * 1. Route is co-located right here (`@PostMapping("/api/appointments")`), eliminating separate Web/Controller layers.
 * 2. Uses standard `java.time.Clock` consistently—eliminating `Common/Interfaces/IDateTime` and `Infrastructure/Services/DateTimeService`.
 * 3. Publishes domain events directly via Spring's `ApplicationEventPublisher`—no intermediate `IDomainEventService` abstraction.
 * 4. Validates once and executes directly—no mediator pipeline boilerplate required to jump between layers.
 */
@RestController
@Tag(name = "Appointments")
class BookAppointmentSlice(
    private val appointments: AppointmentRepository,
    private val patients: PatientRepository,
    private val doctors: DoctorRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(BookAppointmentSlice::class.java)

    data class Request(
        @field:NotNull(message = "PatientId is required")
        val patientId: UUID?,

        @field:NotNull(message = "DoctorId is required")
        val doctorId: UUID?,

        @field:NotNull(message = "Start time is required")
        val start: OffsetDateTime?,

        @field:NotNull(message = "End time is required")
        val end: OffsetDateTime?,

        @field:Size(
            max = SchedulingPolicies.MAX_NOTES_LENGTH,
            message = "Notes cannot exceed ${SchedulingPolicies.MAX_NOTES_LENGTH} characters"
        )
        val notes: String? = null
    )

    data class Response(
        val id: UUID,
        val startUtc: Instant,
        val endUtc: Instant
    )

    @PostMapping("/api/appointments")
    @Operation(summary = "Book a new appointment")
    fun handleEndpoint(@Valid @RequestBody request: Request): ResponseEntity<*> {
        val result = handle(request)
        return result.toResponseEntity { created ->
            ResponseEntity
                .created(URI.create("/api/appointments/${created.id}"))
                .body(created)
        }
    }

    @Transactional
    fun handle(request: Request): AppResult<Response> {
        val patientId = request.patientId!!
        val doctorId = request.doctorId!!
        val startUtc = request.start!!.toInstant()
        val endUtc = request.end!!.toInstant()
        val nowUtc = clock.instant()

        // 1. Validate domain time invariants first (authoritative check using injected Clock)
        val (appointment, bookedEvent) = runCatching {
            Appointment.schedule(
                patientId = patientId,
                doctorId = doctorId,
                startUtc = startUtc,
                endUtc = endUtc,
                notes = request.notes,
                nowUtc = nowUtc
            )
        }.getOrElse { ex ->
            return AppResult.failure(
                AppError.Validation("Appointment.ValidationFailed", ex.message ?: "Invalid appointment parameters")
            )
        }

        // 2. Verify Patient exists
        if (!patients.existsById(patientId)) {
            return AppResult.failure(
                AppError.NotFound("Appointment.PatientNotFound", "Patient with ID $patientId not found")
            )
        }

        // 3. Verify Doctor exists
        if (!doctors.existsById(doctorId)) {
            return AppResult.failure(
                AppError.NotFound("Appointment.DoctorNotFound", "Doctor with ID $doctorId not found")
            )
        }

        // 4. Check for overlapping scheduled appointments for this doctor
        if (appointments.hasOverlappingAppointment(doctorId, startUtc, endUtc)) {
            return AppResult.failure(
                AppError.Conflict(
                    "Appointment.Conflict",
                    "Doctor has a conflicting appointment during the requested time"
                )
            )
        }

        // 5. Persist & publish domain event
        runCatching {
            appointments.saveAndFlush(appointment)
        }.onFailure { ex ->
            if (ex is DataIntegrityViolationException) {
                logger.warn(
                    "Concurrent booking conflict for DoctorId={}, StartUtc={}, EndUtc={}",
                    doctorId, startUtc, endUtc, ex
                )
                return AppResult.failure(
                    AppError.Conflict(
                        "Appointment.Conflict",
                        "Doctor has a conflicting appointment during the requested time"
                    )
                )
            }
            throw ex
        }

        events.publishEvent(bookedEvent)

        return AppResult.success(
            Response(
                id = appointment.id,
                startUtc = appointment.startUtc,
                endUtc = appointment.endUtc
            )
        )
    }
}
