package dev.verticalslice.scheduling.features.appointments

import com.fasterxml.jackson.databind.ObjectMapper
import dev.verticalslice.scheduling.features.appointments.domain.AppointmentBookedEvent
import dev.verticalslice.scheduling.features.appointments.domain.AppointmentCompletedEvent
import dev.verticalslice.scheduling.features.appointments.domain.AppointmentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@SpringBootTest
@AutoConfigureMockMvc
class AppointmentSlicesIntegrationTest {

    @TestConfiguration
    class FixedClockAndEventRecorderConfig {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

        @Bean
        fun testEventCollector() = TestEventCollector()
    }

    class TestEventCollector {
        val bookedEvents = CopyOnWriteArrayList<AppointmentBookedEvent>()
        val completedEvents = CopyOnWriteArrayList<AppointmentCompletedEvent>()

        @EventListener
        fun onBooked(event: AppointmentBookedEvent) {
            bookedEvents.add(event)
        }

        @EventListener
        fun onCompleted(event: AppointmentCompletedEvent) {
            completedEvents.add(event)
        }

        fun clear() {
            bookedEvents.clear()
            completedEvents.clear()
        }
    }

    companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-10-24T09:00:00Z")
        val PATIENT_JOHN: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val PATIENT_JANE: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val DOCTOR_SARAH: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var appointmentRepository: AppointmentRepository

    @Autowired
    private lateinit var eventCollector: TestEventCollector

    @BeforeEach
    fun setup() {
        appointmentRepository.deleteAll()
        eventCollector.clear()
    }

    @Test
    fun `BookAppointmentSlice books a valid appointment and publishes AppointmentBookedEvent`() {
        val start = OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(3600), ZoneOffset.UTC)
        val end = start.plusMinutes(30)

        val payload = mapOf(
            "patientId" to PATIENT_JOHN,
            "doctorId" to DOCTOR_SARAH,
            "start" to start.toString(),
            "end" to end.toString(),
            "notes" to "Annual checkup"
        )

        mockMvc.post("/api/appointments") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(payload)
        }.andExpect {
            status { isCreated() }
            header { exists("Location") }
            jsonPath("$.id") { isNotEmpty() }
            jsonPath("$.startUtc") { value(start.toInstant().toString()) }
            jsonPath("$.endUtc") { value(end.toInstant().toString()) }
        }

        assertEquals(1, eventCollector.bookedEvents.size)
        assertEquals(PATIENT_JOHN, eventCollector.bookedEvents.first().patientId)
    }

    @Test
    fun `BookAppointmentSlice rejects double-booking for the same doctor with 409 Conflict`() {
        val start = OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(7200), ZoneOffset.UTC)
        val end = start.plusMinutes(45)

        val firstBooking = mapOf(
            "patientId" to PATIENT_JOHN,
            "doctorId" to DOCTOR_SARAH,
            "start" to start.toString(),
            "end" to end.toString()
        )

        mockMvc.post("/api/appointments") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(firstBooking)
        }.andExpect {
            status { isCreated() }
        }

        // Overlapping booking (starts 15 minutes into the first appointment)
        val overlappingBooking = mapOf(
            "patientId" to PATIENT_JANE,
            "doctorId" to DOCTOR_SARAH,
            "start" to start.plusMinutes(15).toString(),
            "end" to end.plusMinutes(15).toString()
        )

        mockMvc.post("/api/appointments") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(overlappingBooking)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.title") { value("Appointment.Conflict") }
        }
    }

    @Test
    fun `BookAppointmentSlice rejects booking less than 15 minutes in advance using injected Clock`() {
        val tooSoonStart = OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(5 * 60), ZoneOffset.UTC)
        val end = tooSoonStart.plusMinutes(30)

        val payload = mapOf(
            "patientId" to PATIENT_JOHN,
            "doctorId" to DOCTOR_SARAH,
            "start" to tooSoonStart.toString(),
            "end" to end.toString()
        )

        mockMvc.post("/api/appointments") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(payload)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.title") { value("Appointment.ValidationFailed") }
        }
    }

    @Test
    fun `CompleteAppointmentSlice and CancelAppointmentSlice enforce terminal state lifecycle`() {
        val start = OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(3600), ZoneOffset.UTC)
        val end = start.plusMinutes(30)

        val createResult = mockMvc.post("/api/appointments") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf(
                    "patientId" to PATIENT_JOHN,
                    "doctorId" to DOCTOR_SARAH,
                    "start" to start.toString(),
                    "end" to end.toString()
                )
            )
        }.andReturn()

        val createdId = objectMapper.readTree(createResult.response.contentAsString).get("id").asText()

        // Complete the appointment
        mockMvc.post("/api/appointments/$createdId/complete") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("notes" to "Patient vitals normal"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.completedUtc") { value(FIXED_NOW.toString()) }
        }

        assertTrue(eventCollector.completedEvents.any { it.appointmentId.toString() == createdId })

        // Attempting to cancel a completed appointment must fail with 400 Bad Request
        mockMvc.post("/api/appointments/$createdId/cancel") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("reason" to "Changed mind"))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.title") { value("Appointment.CannotCancel") }
        }
    }

    @Test
    fun `GetAppointmentsSlice and GetAppointmentByIdSlice return enriched projections`() {
        val start = OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(3600), ZoneOffset.UTC)
        val end = start.plusMinutes(30)

        val createResult = mockMvc.post("/api/appointments") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf(
                    "patientId" to PATIENT_JOHN,
                    "doctorId" to DOCTOR_SARAH,
                    "start" to start.toString(),
                    "end" to end.toString(),
                    "notes" to "Follow-up"
                )
            )
        }.andReturn()

        val createdId = objectMapper.readTree(createResult.response.contentAsString).get("id").asText()

        mockMvc.get("/api/appointments/$createdId")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(createdId) }
                jsonPath("$.patientName") { value("John Smith") }
                jsonPath("$.doctorName") { value("Dr. Sarah Wilson") }
                jsonPath("$.doctorSpecialty") { value("Family Medicine") }
            }

        mockMvc.get("/api/appointments?patientId=$PATIENT_JOHN&pageNumber=1&pageSize=10")
            .andExpect {
                status { isOk() }
                jsonPath("$.totalCount") { value(1) }
                jsonPath("$.items[0].id") { value(createdId) }
            }
    }
}
