package dev.verticalslice.scheduling.features.appointments

import dev.verticalslice.scheduling.features.appointments.domain.AppointmentBookedEvent
import dev.verticalslice.scheduling.features.appointments.domain.AppointmentCompletedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Feature-local side effects triggered when an appointment is booked or completed.
 *
 * Compared to traditional Clean Architecture:
 * - No `IDomainEventService` in `Application/Common/Interfaces`
 * - No `DomainEventService` in `Infrastructure/Services`
 * - No generic notification wrapper classes
 *
 * Instead, the listener lives right next to the slices inside `features/appointments/` and listens
 * directly to the strongly-typed Kotlin data class event.
 */
@Component
class OnAppointmentLifecycleEvents {
    private val logger = LoggerFactory.getLogger(OnAppointmentLifecycleEvents::class.java)

    @EventListener
    fun onAppointmentBooked(event: AppointmentBookedEvent) {
        logger.info(
            "Appointment booked: {} for Patient {} with Doctor {} from {} to {}",
            event.appointmentId,
            event.patientId,
            event.doctorId,
            event.startUtc,
            event.endUtc
        )
    }

    @EventListener
    fun onAppointmentCompleted(event: AppointmentCompletedEvent) {
        logger.info(
            "Appointment completed: {} for Patient {} with Doctor {} at {}",
            event.appointmentId,
            event.patientId,
            event.doctorId,
            event.completedUtc
        )
    }
}
