package dev.verticalslice.scheduling.shared.error

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import java.net.URI

/**
 * Idiomatic Kotlin functional result type (replacing C#'s ErrorOr library).
 * Zero reflection, zero dynamic casts, and exhaustive `when` matching at compile time.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>

    companion object {
        fun <T> success(value: T): AppResult<T> = Success(value)
        fun failure(error: AppError): AppResult<Nothing> = Failure(error)
    }
}

sealed class AppError(
    val code: String,
    val message: String,
    val status: HttpStatus
) {
    class NotFound(code: String, message: String) :
        AppError(code, message, HttpStatus.NOT_FOUND)

    class Conflict(code: String, message: String) :
        AppError(code, message, HttpStatus.CONFLICT)

    class Validation(code: String, message: String, val fieldErrors: Map<String, String> = emptyMap()) :
        AppError(code, message, HttpStatus.BAD_REQUEST)
}

/**
 * Maps an [AppResult] directly to a Spring [ResponseEntity] using RFC 7807 [ProblemDetail] on failure.
 */
inline fun <T> AppResult<T>.toResponseEntity(
    onSuccess: (T) -> ResponseEntity<*> = { ResponseEntity.ok(it) }
): ResponseEntity<*> = when (this) {
    is AppResult.Success -> onSuccess(value)
    is AppResult.Failure -> {
        val problem = ProblemDetail.forStatusAndDetail(error.status, error.message).apply {
            title = error.code
            type = URI.create("https://verticalslice.dev/errors/${error.code.lowercase().replace('.', '-')}")
            setProperty("errorCode", error.code)
            if (error is AppError.Validation && error.fieldErrors.isNotEmpty()) {
                setProperty("errors", error.fieldErrors)
            }
        }
        ResponseEntity.status(error.status).body(problem)
    }
}
