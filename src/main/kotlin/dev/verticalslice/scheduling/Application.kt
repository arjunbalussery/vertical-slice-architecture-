package dev.verticalslice.scheduling

import dev.verticalslice.scheduling.features.appointments.domain.Doctor
import dev.verticalslice.scheduling.features.appointments.domain.DoctorRepository
import dev.verticalslice.scheduling.features.appointments.domain.Patient
import dev.verticalslice.scheduling.features.appointments.domain.PatientRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.util.UUID

@SpringBootApplication
class Application {

    /**
     * Standard JDK Clock bean—replaces the Clean Architecture anti-pattern of creating
     * `Common/Interfaces/IDateTime.cs` and `Infrastructure/Services/DateTimeService.cs`.
     * In tests, we can easily inject `Clock.fixed(...)`.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /**
     * Seeds sample patients and doctors matching the reference clinic domain.
     */
    @Bean
    fun seedReferenceData(
        patients: PatientRepository,
        doctors: DoctorRepository
    ) = CommandLineRunner {
        if (patients.count() == 0L) {
            patients.saveAll(
                listOf(
                    Patient(
                        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        firstName = "John",
                        lastName = "Smith",
                        email = "john.smith@example.com"
                    ),
                    Patient(
                        id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        firstName = "Jane",
                        lastName = "Doe",
                        email = "jane.doe@example.com"
                    ),
                    Patient(
                        id = UUID.fromString("33333333-3333-3333-3333-333333333333"),
                        firstName = "Bob",
                        lastName = "Johnson",
                        email = "bob.johnson@example.com"
                    )
                )
            )
        }

        if (doctors.count() == 0L) {
            doctors.saveAll(
                listOf(
                    Doctor(
                        id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        firstName = "Sarah",
                        lastName = "Wilson",
                        specialty = "Family Medicine"
                    ),
                    Doctor(
                        id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                        firstName = "Michael",
                        lastName = "Chen",
                        specialty = "Cardiology"
                    ),
                    Doctor(
                        id = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                        firstName = "Emily",
                        lastName = "Rodriguez",
                        specialty = "Pediatrics"
                    )
                )
            )
        }
    }
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
