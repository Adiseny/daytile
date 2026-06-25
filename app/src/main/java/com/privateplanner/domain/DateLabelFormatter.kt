package com.privateplanner.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateLabelFormatter {
    private var formatterLocale = Locale.getDefault()
    private var dateFormatter = buildDateFormatter(formatterLocale)
    private var dateWithYearFormatter = buildDateWithYearFormatter(formatterLocale)

    fun primaryLabel(date: LocalDate): String {
        refreshFormattersIfNeeded()
        val today = LocalDate.now()
        return when (date) {
            today.minusDays(1) -> "Yesterday"
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> {
                if (date.year == today.year) {
                    date.format(dateFormatter)
                } else {
                    date.format(dateWithYearFormatter)
                }
            }
        }
    }

    fun secondaryLabel(date: LocalDate): String? {
        refreshFormattersIfNeeded()
        val today = LocalDate.now()
        return when (date) {
            today.minusDays(1), today, today.plusDays(1) -> date.format(dateFormatter)
            else -> null
        }
    }

    private fun refreshFormattersIfNeeded() {
        val currentLocale = Locale.getDefault()
        if (currentLocale != formatterLocale) {
            formatterLocale = currentLocale
            dateFormatter = buildDateFormatter(currentLocale)
            dateWithYearFormatter = buildDateWithYearFormatter(currentLocale)
        }
    }

    private fun buildDateFormatter(locale: Locale): DateTimeFormatter {
        return DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)
    }

    private fun buildDateWithYearFormatter(locale: Locale): DateTimeFormatter {
        return DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)
    }
}
