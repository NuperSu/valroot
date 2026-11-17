package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The screen a host shows before the application exists: while local data opens ([failed] false, with a progress bar
 * and [message], when not empty, as the work in progress) or when it could not be opened. [themeKey] is the appearance last saved by the
 * host, so the screen matches the app it leads into; without one it uses the app's default appearance.
 */
@Composable
fun ValrootStartScreen(
    themeKey: String?,
    failed: Boolean,
    message: String,
) {
    ValrootTheme(AppearanceTheme.fromKey(themeKey.orEmpty())) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(28.dp)
                    .testTag("start-screen"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    if (failed) "Could not open local data" else "Opening your local portfolio",
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink,
                )
                if (!failed) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal)
                val detail = if (failed) "Check storage and app compatibility. Saved data is intact." else message
                if (detail.isNotEmpty()) Text(detail, color = Muted)
            }
        }
    }
}

/** Whether the appearance saved under [themeKey] is dark, for a host's system bar icons. */
fun valrootThemeIsDark(
    themeKey: String?,
    systemDark: Boolean,
): Boolean =
    when (AppearanceTheme.fromKey(themeKey.orEmpty())) {
        AppearanceTheme.DARK -> true
        AppearanceTheme.LIGHT -> false
        AppearanceTheme.SYSTEM -> systemDark
    }
