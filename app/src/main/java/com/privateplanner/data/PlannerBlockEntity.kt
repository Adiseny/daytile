package com.privateplanner.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blocks",
    indices = [
        Index(value = ["dateEpochDay", "startMinutes"]),
        Index(value = ["title", "dateEpochDay", "startMinutes", "durationMinutes"])
    ]
)
data class PlannerBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val title: String,
    val startMinutes: Int,
    val durationMinutes: Int
)
