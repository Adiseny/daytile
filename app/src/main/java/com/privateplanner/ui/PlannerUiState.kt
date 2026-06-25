package com.privateplanner.ui

import com.privateplanner.domain.PlannerBlock
import java.time.LocalDate

data class PlannerUiState(
    val selectedDate: LocalDate,
    val blocksByDate: Map<LocalDate, List<PlannerBlock>>,
    val selectedDateLoaded: Boolean,
    val sheet: PlannerSheet?,
    val snackbar: PlannerSnackbar?,
    val createError: String?,
    val scrollTargetMinutes: Int?
)

enum class MovePlacement {
    Invalid,
    TransientOnly,
    Savable
}

sealed interface PlannerSheet {
    data class CreateBlock(val startMinutes: Int) : PlannerSheet
    data class RenameBlock(val blockId: Long) : PlannerSheet
    data class BlockActions(val blockId: Long) : PlannerSheet
    data object DateJump : PlannerSheet
}

data class PlannerSnackbar(
    val id: Long,
    val deletedBlock: PlannerBlock
)
