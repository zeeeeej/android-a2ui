package com.xpl.a2uichat.a2ui

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults as Defaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

const val CHAT_CATALOG_ID = "https://xpl.dev/a2ui/chat/v1/catalog.json"

// A lazy List inside the chat's LazyColumn must have a finite height.
private object BoundedList : A2uiComponent by Defaults.list {
    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        with(Defaults.list) { Content(properties, modifier.heightIn(max = 360.dp)) }
    }
}

val chatCatalog by lazy {
    A2uiCatalog(
        catalogId = CHAT_CATALOG_ID,
        components = listOf(Defaults.text, Defaults.row, Defaults.column, Defaults.card,
            BoundedList, Defaults.button, Defaults.textField, Defaults.checkBox, Defaults.divider),
        isInline = true,
    )
}
