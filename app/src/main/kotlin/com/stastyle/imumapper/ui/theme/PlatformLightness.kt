package com.stastyle.imumapper.ui.theme

import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.stastyle.imumapper.R

/**
 * Sets `android:isLightTheme` in this platform theme to match the app's theme. The platform's floating
 * Cut/Copy/Paste toolbar reads it from its window's theme each time it opens, and draws itself dark while it
 * is false, which is what `Theme.ImuMapper`'s dark parent leaves. `applyStyle` changes the theme in place, so
 * nothing is recreated, and a configuration change rebuilds the theme with the style still applied.
 */
fun Resources.Theme.applyPlatformLightness(dark: Boolean) {
    val overlay =
        if (dark) R.style.ThemeOverlay_ImuMapper_PlatformDark else R.style.ThemeOverlay_ImuMapper_PlatformLight
    applyStyle(overlay, true)
}

/**
 * [applyPlatformLightness] for the window of a Material3 bottom sheet, called at the top of the content of a
 * sheet that holds a text field. The sheet's window copies the activity's theme and then lays Material3's
 * dialog theme over it, whose parent `android:Theme.DeviceDefault.Dialog` is dark and sets the attribute back
 * to false. Inside the sheet `LocalContext` is that window's own context. Compose's AlertDialog keeps the
 * activity's value and needs nothing.
 */
@Composable
fun MatchSheetWindowTheme() {
    val context = LocalContext.current
    val dark = !MaterialTheme.imuColors.isLight
    DisposableEffect(context, dark) {
        context.theme.applyPlatformLightness(dark)
        onDispose {}
    }
}
