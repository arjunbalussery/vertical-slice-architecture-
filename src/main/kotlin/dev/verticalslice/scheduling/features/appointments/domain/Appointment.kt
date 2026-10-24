package dev.verticalslice.scheduling.features.appointments.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class AppointmentStatus {
    SCHEDULED,
    COMPLETED,
    CANCELLED
}

/**
 * Domain events for Appointment lifecycle.
 * Notice: No `IDomainEventService` or reflection-based `DomainEventNotification<T>` wrapper needed!
 */
data class AppointmentBookedEvent(
    val appointmentId: UUID,
    val patientId: UUID,
    val doctorId: UUID,
    val startUtc: Instant,
    val endUtc: Instant
)

data class AppointmentCompletedEvent(
    val appointmentId: UUID,
    val patientId: UUID,
    val doctorId: UUID,
    val completedUtc: Instant,
    val notes: String?
)

/**
 * Rich domain entity co-located with its persistence mapping inside the `appointments` feature.
 * Notice we don't scatter `AppointmentConfiguration.cs` into a distant `Infrastructure/Persistence/Configurations` folder.
 */
@Entity
@Table(
    name = "appointments",
    uniqueConstraints = [
        UniqueConstraint(
            name = "ix_appointments_doctor_timerange",
            columnNames = ["doctor_id", "start_utc", "end_utc"]
        )
    ],
    indexes = [
        Index(name = "ix_appointments_patient_starttime", columnList = "patient_id, start_utc")
    ]
)
class Appointment private constructor(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "patient_id", nullable = false)
    val patientId: UUID,

    @Column(name = "doctor_id", nullable = false)
    val doctorId: UUID,

    @Column(name = "start_utc", nullable = false)
    val startUtc: Instant,

    @Column(name = "end_utc", nullable = false)
    val endUtc: Instant,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: AppointmentStatus = AppointmentStatus.SCHEDULED,

    @Column(length = SchedulingPolicies.MAX_NOTES_LENGTH)
    var notes: String? = null,

    @Column(name = "completed_utc")
    var completedUtc: Instant? = null,

    @Column(name = "cancelled_utc")
    var cancelledUtc: Instant? = null,

    @Column(name = "cancellation_reason", length = SchedulingPolicies.MAX_CANCELLATION_REASON_LENGTH)
    var cancellationReason: String? = null,

    @Column(name = "created_utc", nullable = false)
    val createdUtc: Instant = Instant.now(),

    @Column(name = "last_modified_utc", nullable = false)
    var lastModifiedUtc: Instant = createdUtc,

    @Version
    var version: Long = 0
) {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", insertable = false, updatable = false)
    var patient: Patient? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", insertable = false, updatable = false)
    var doctor: Doctor? = null

    companion object {
        fun schedule(
            patientId: UUID,
            doctorId: UUID,
            startUtc: Instant,
            endUtc: Instant,
            notes: String?,
            nowUtc: Instant
        ): Pair<Appointment, AppointmentBookedEvent> {
            require(startUtc.isBefore(endUtc)) {
                "Start time must be before end time"
            }

            val duration = Duration.between(startUtc, endUtc)
            require(duration.toMinutes() >= SchedulingPolicies.MINIMUM_APPOINTMENT_DURATION_MINUTES) {
                "Appointment must be at least ${SchedulingPolicies.MINIMUM_APPOINTMENT_DURATION_MINUTES} minutes long"
            }
            require(duration.toMinutes() <= SchedulingPolicies.MAXIMUM_APPOINTMENT_DURATION_HOURS * 60) {
                "Appointment cannot be longer than ${SchedulingPolicies.MAXIMUM_APPOINTMENT_DURATION_HOURS} hours"
            }
            require(startUtc.isAfter(nowUtc.plusSeconds(SchedulingPolicies.MINIMUM_BOOKING_ADVANCE_MINUTES * 60))) {
                "Appointment must be scheduled at least ${SchedulingPolicies.MINIMUM_BOOKING_ADVANCE_MINUTES} minutes in advance"
            }

            val sanitizedNotes = notes?.trim()?.takeIf { it.isNotEmpty() }
            if (sanitizedNotes != null) {
                require(sanitizedNotes.length <= SchedulingPolicies.MAX_NOTES_LENGTH) {
                    "Notes cannot exceed ${SchedulingPolicies.MAX_NOTES_LENGTH} characters"
                }
            }

            val appointment = Appointment(
                patientId = patientId,
                doctorId = doctorId,
                startUtc = startUtc,
                endUtc = endUtc,
                notes = sanitizedNotes,
                createdUtc = nowUtc,
                lastModifiedUtc = nowUtc
            )

            val event = AppointmentBookedEvent(
                appointmentId = appointment.id,
                patientId = appointment.patientId,
                doctorId = appointment.doctorId,
                startUtc = appointment.startUtc,
                endUtc = appointment.endUtc
            )

            return appointment to event
        }
    }

    /**
     * Marks the appointment as completed. Idempotent if already completed.
     * Note how `nowUtc` is passed explicitly from our injected `java.time.Clock`—never calling `Instant.now()` behind the scenes!
     */
    fun complete(completionNotes: String? = null, nowUtc: Instant): AppointmentCompletedEvent? {
        check(status != AppointmentStatus.CANCELLED) {
            "Cannot complete a cancelled appointment"
        }

        if (status == AppointmentStatus.COMPLETED) {
            return null // Idempotent
        }

        val sanitizedNotes = completionNotes?.trim()?.takeIf { it.isNotEmpty() }
        if (sanitizedNotes != null) {
            require(sanitizedNotes.length <= SchedulingPolicies.MAX_NOTES_LENGTH) {
                "Notes cannot exceed ${SchedulingPolicies.MAX_NOTES_LENGTH} characters"
            }
        }

        status = AppointmentStatus.COMPLETED
        completedUtc = nowUtc
        notes = sanitizedNotes ?: notes
        lastModifiedUtc = nowUtc

        return AppointmentCompletedEvent(
            appointmentId = id,
            patientId = patientId,
            doctorId = doctorId,
            completedUtc = nowUtc,
            notes = notes
        )
    }

    /**
     * Cancels the appointment. Idempotent if already cancelled.
     */
    fun cancel(reason: String, nowUtc: Instant) {
        val trimmedReason = reason.trim()
        require(trimmedReason.isNotEmpty()) {
            "Cancellation reason is required"
        }
        require(trimmedReason.length <= SchedulingPolicies.MAX_CANCELLATION_REASON_LENGTH) {
            "Cancellation reason cannot exceed ${SchedulingPolicies.MAX_CANCELLATION_REASON_LENGTH} characters"
        }
        check(status != AppointmentStatus.COMPLETED) {
            "Cannot cancel a completed appointment"
        }

        if (status == AppointmentStatus.CANCELLED) {
            return // Idempotent
        }

        status = AppointmentStatus.CANCELLED
        cancelledUtc = nowUtc
        cancellationReason = trimmedReason
        lastModifiedUtc = nowUtc
    }
}

interface AppointmentRepository : JpaRepository<Appointment, UUID> {

    @Query(
        """
        SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END
        FROM Appointment a
        WHERE a.doctorId = :doctorId
          AND a.status = 'SCHEDULED'
          AND a.startUtc < :endUtc
          AND a.endUtc > :startUtc
        """
    )
    fun hasOverlappingAppointment(
        @Param("doctorId") doctorId: UUID,
        @Param("startUtc") startUtc: Instant,
        @Param("endUtc") endUtc: Instant
    ): Boolean

    @Query(
        """
        SELECT a FROM Appointment a
        JOIN FETCH a.patient
        JOIN FETCH a.doctor
        WHERE a.id = :id
        """
    )
    fun findByIdWithDetails(@Param("id") id: UUID): Appointment?

    @Query(
        value = """
        SELECT a FROM Appointment a
        JOIN FETCH a.patient
        JOIN FETCH a.doctor
        WHERE (:patientId IS NULL OR a.patientId = :patientId)
          AND (:doctorId IS NULL OR a.doctorId = :doctorId)
          AND (:status IS NULL OR a.status = :status)
          AND (:startDate IS NULL OR a.startUtc >= :startDate)
          AND (:endDate IS NULL OR a.endUtc <= :endDate)
        ORDER BY a.startUtc ASC
        """,
        countQuery = """
        SELECT COUNT(a) FROM Appointment a
        WHERE (:patientId IS NULL OR a.patientId = :patientId)
          AND (:doctorId IS NULL OR a.doctorId = :doctorId)
          AND (:status IS NULL OR a.status = :status)
          AND (:startDate IS NULL OR a.startUtc >= :startDate)
          AND (:endDate IS NULL OR a.endUtc <= :endDate)
        """
    )
    fun findFiltered(
        @Param("patientId") patientId: UUID?,
        @Param("doctorId") doctorId: UUID?,
        @Param("status") status: AppointmentStatus?,
        @Param("startDate") startDate: Instant?,
        @Param("endDate") endDate: Instant?,
        pageable: Pageable
    ): Page<Appointment>
}
