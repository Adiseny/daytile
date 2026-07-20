package com.privateplanner.ui

import com.privateplanner.domain.PlannerBlock
import java.time.LocalDate

data class PlannerUiState(
    val selectedDate: LocalDate,
    val blocksByDate: Map<LocalDate, List<PlannerBlock>>,
    val sheet: PlannerSheet?,
    val snackbar: PlannerSnackbar?,
    val sheetError: String?,
    val scrollTargetMinutes: Int?
)

sealed interface PlannerSheet {
    class CreateBlock(val startMinutes: Int) : PlannerSheet
    class RenameBlock(val blockId: Long) : PlannerSheet
    class BlockActions(val blockId: Long) : PlannerSheet
    object DateJump : PlannerSheet
}

sealed interface PlannerSnackbar {
    val id: Long

    class Deleted(
        override val id: Long,
        val deletedBlock: PlannerBlock
    ) : PlannerSnackbar

    class Message(
        override val id: Long,
        val message: String
    ) : PlannerSnackbar
}
