package com.privateplanner.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager

// The one place the window is styled: edge to edge, the paper as its background, clear
// status bar (the heading owns the tint behind its icons), and a navigation bar in the
// paper, dimmed while a sheet is open, with icons to suit. Sheets must not style the bars
// themselves. On Android 15 and later the bars are always clear, and the screen paints the
// navigation bar's colour behind its buttons itself (PlannerScreen.navigationBarColour).
@Suppress("DEPRECATION")
internal fun Activity.applyPlannerSystemBars(palette: PlannerPalette, dimmed: Boolean = false) {
    // Paper, dim and polarity fix every colour below, and re-applying an unchanged style
    // would still update the window, so each distinct style is applied once.
    val style = (palette.Paper.toLong() shl 2) or (if (dimmed) 2L else 0L) or (if (palette.LightBackground) 1L else 0L)
    val decor = window.decorView
    val applied = decor.tag as? Long
    if (applied == style) return
    decor.tag = style
    // The window background is the paper, so the planner paints nothing beneath its
    // content and the screen is filled once per frame instead of twice.
    if (applied == null || applied shr 2 != style shr 2) window.setBackgroundDrawable(ColorDrawable(palette.Paper))
    if (applied == null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            decor.systemUiVisibility = decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) Color.TRANSPARENT else navigationBarColour(palette, dimmed)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    val darkIcons = palette.LightBackground && !dimmed
    val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    decor.systemUiVisibility = if (darkIcons) decor.systemUiVisibility or light else decor.systemUiVisibility and light.inv()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (darkIcons) appearance else 0, appearance)
    }
}

internal fun navigationBarColour(palette: PlannerPalette, dimmed: Boolean): Int =
    if (dimmed) compositeOver(palette.Scrim, palette.Paper) else palette.Paper
