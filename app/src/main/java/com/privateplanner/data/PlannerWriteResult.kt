package com.privateplanner.data

sealed interface PlannerWriteResult {
    data object Success : PlannerWriteResult
    data object NoSpace : PlannerWriteResult
    data object MissingBlock : PlannerWriteResult
    data object RejectedOverlap : PlannerWriteResult
    data object InvalidInput : PlannerWriteResult
    data class Failed(val throwable: Throwable) : PlannerWriteResult
}
