package dev.verticalslice.scheduling.shared.pagination

import kotlin.math.ceil

data class PaginatedList<T>(
    val items: List<T>,
    val pageNumber: Int,
    val pageSize: Int,
    val totalCount: Long,
    val totalPages: Int = if (pageSize > 0) ceil(totalCount.toDouble() / pageSize).toInt() else 0
) {
    val hasPreviousPage: Boolean get() = pageNumber > 1
    val hasNextPage: Boolean get() = pageNumber < totalPages
}
