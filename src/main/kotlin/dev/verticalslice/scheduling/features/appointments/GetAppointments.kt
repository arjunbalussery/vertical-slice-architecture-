package dev.verticalslice.scheduling.features.appointments

import dev.verticalslice.scheduling.features.appointments.domain.AppointmentRepository
import dev.verticalslice.scheduling.features.appointments.domain.AppointmentStatus
import dev.verticalslice.scheduling.shared.error.AppError
import dev.verticalslice.scheduling.shared.error.AppResult
import dev.verticalslice.scheduling.shared.error.toResponseEntity
import dev.verticalslice.scheduling.shared.pagination.PaginatedList
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * VERTICAL SLICE: Get a paginated list of appointments with optional filters (`GET /api/appointments`).
 */
@RestController
@Tag(name = "Appointments")
class GetAppointmentsSlice(
    private val appointments: AppointmentRepository
) {
    data class Request(
        val patientId: UUID? = null,
        val doctorId: UUID? = null,
        val status: AppointmentStatus? = null,
        val startDate: Instant? = null,
        val endDate: Instant? = null,
        val pageNumber: Int = 1,
        val pageSize: Int = 10
    )

    @GetMapping("/api/appointments")
    @Operation(summary = "Get a paginated list of appointments with optional filters")
    fun handleEndpoint(
        @RequestParam(required = false) patientId: UUID?,
        @RequestParam(required = false) doctorId: UUID?,
        @RequestParam(required = false) status: AppointmentStatus?,
        @RequestParam(required = false) startDate: Instant?,
        @RequestParam(required = false) endDate: Instant?,
        @RequestParam(defaultValue = "1") pageNumber: Int,
        @RequestParam(defaultValue = "10") pageSize: Int
    ): ResponseEntity<*> {
        val request = Request(
            patientId = patientId,
            doctorId = doctorId,
            status = status,
            startDate = startDate,
            endDate = endDate,
            pageNumber = pageNumber,
            pageSize = pageSize
        )
        return handle(request).toResponseEntity()
    }

    @Transactional(readOnly = true)
    fun handle(request: Request): AppResult<PaginatedList<AppointmentSummaryResponse>> {
        if (request.pageNumber < 1) {
            return AppResult.failure(
                AppError.Validation("Pagination.InvalidPageNumber", "PageNumber must be at least 1")
            )
        }
        if (request.pageSize !in 1..100) {
            return AppResult.failure(
                AppError.Validation("Pagination.InvalidPageSize", "PageSize must be between 1 and 100")
            )
        }
        if (request.startDate != null && request.endDate != null && request.startDate.isAfter(request.endDate)) {
            return AppResult.failure(
                AppError.Validation("Filter.InvalidDateRange", "StartDate must be before or equal to EndDate")
            )
        }

        val pageRequest = PageRequest.of(request.pageNumber - 1, request.pageSize)
        val page = appointments.findFiltered(
            patientId = request.patientId,
            doctorId = request.doctorId,
            status = request.status,
            startDate = request.startDate,
            endDate = request.endDate,
            pageable = pageRequest
        )

        return AppResult.success(
            PaginatedList(
                items = page.content.map(AppointmentSummaryResponse::from),
                pageNumber = request.pageNumber,
                pageSize = request.pageSize,
                totalCount = page.totalElements
            )
        )
    }
}
