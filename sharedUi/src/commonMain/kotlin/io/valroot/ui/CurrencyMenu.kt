package io.valroot.ui

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.valroot.core.application.DISPLAY_CURRENCIES
import io.valroot.core.application.PLANNED_CURRENCIES

/**
 * Display currency choices for a dropdown: the available ones, then the planned ones dimmed. Prices are fetched in US
 * dollars only until they can be converted with an exchange rate.
 */
@Composable
internal fun CurrencyMenuItems(
    exclude: String? = null,
    label: (String) -> String = { it },
    onPick: (String) -> Unit,
) {
    DISPLAY_CURRENCIES.filter { it != exclude }.forEach { code ->
        DropdownMenuItem(text = { Text(label(code)) }, onClick = { onPick(code) }, modifier = Modifier.testTag("currency-$code"))
    }
    PLANNED_CURRENCIES.forEach { code ->
        DropdownMenuItem(
            text = { Text("${label(code)} · planned") },
            onClick = {},
            enabled = false,
            modifier = Modifier.testTag("currency-$code"),
        )
    }
}
