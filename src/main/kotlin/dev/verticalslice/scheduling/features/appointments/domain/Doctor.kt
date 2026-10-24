package dev.verticalslice.scheduling.features.appointments.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

@Entity
@Table(name = "doctors")
class Doctor(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 100)
    val firstName: String,

    @Column(nullable = false, length = 100)
    val lastName: String,

    @Column(nullable = false, length = 100)
    val specialty: String
) {
    val fullName: String
        get() = "Dr. $firstName $lastName"
}

interface DoctorRepository : JpaRepository<Doctor, UUID>
