package dev.verticalslice.scheduling.shared.error

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/**
 * Single, unified validation & exception handler producing RFC 7807 Problem Details.
 *
 * Standard Spring `@Valid` on the slice request DTO handles fast-fail input validation in a single pass.
 */
@RestControllerAdvice
class GlobalProblemDetailsAdvice {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationErrors(ex: MethodArgumentNotValidException): ProblemDetail {
        val fieldErrors = ex.bindingResult.fieldErrors.associate {
            it.field to (it.defaultMessage ?: "Invalid value")
        }
        val globalErrors = ex.bindingResult.globalErrors.associate {
            it.objectName to (it.defaultMessage ?: "Invalid request")
        }
        val allErrors = fieldErrors + globalErrors

        return ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "One or more validation errors occurred."
        ).apply {
            title = "Request.ValidationFailed"
            type = URI.create("https://verticalslice.dev/errors/validation-failed")
            setProperty("errorCode", "Request.ValidationFailed")
            setProperty("errors", allErrors)
        }
    }
}
