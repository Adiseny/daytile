package com.privateplanner.data

enum class PlannerWriteResult {
    Success,
    NoSpace,
    MissingBlock,
    RejectedOverlap,
    InvalidInput,
    Failed
}
