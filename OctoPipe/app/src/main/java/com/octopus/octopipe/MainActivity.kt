package com.octopus.octopipe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AppBackground = Color(0xFF090B10)
private val SurfaceColor = Color(0xFF141923)
private val SurfaceElevated = Color(0xFF1B2330)
private val Accent = Color(0xFF55A7FF)
private val MainText = Color(0xFFF3F6FA)
private val MutedText = Color(0xFF9AA7B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OctoPipeApp() }
    }
}

private enum class Destination(val label: String) {
    Home("Home"),
    Explore("Explore"),
    Library("Library"),
    Settings("Settings")
}

@Composable
private fun OctoPipeApp() {
    var selected by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AppBackground,
            contentColor = MainText
        ) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val wideLayout = maxWidth >= 600.dp
                Row(Modifier.fillMaxSize()) {
                    if (wideLayout) {
                        NavigationRail(
                            containerColor = AppBackground,
                            contentColor = MainText,
                            header = {
                                OctoMark(Modifier.padding(vertical = 20.dp))
                            }
                        ) {
                            Destination.entries.forEachIndexed { index, destination ->
                                NavigationRailItem(
                                    selected = selected == index,
                                    onClick = { selected = index },
                                    icon = { DestinationIcon(index) },
                                    label = { Text(destination.label) }
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                    ) {
                        TopBar()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (Destination.entries[selected]) {
                                Destination.Home -> HomeScreen(
                                    query = query,
                                    onQueryChange = { query = it },
                                    onExplore = { selected = 1 }
                                )
                                Destination.Explore -> ExploreScreen(
                                    query = query,
                                    onQueryChange = { query = it }
                                )
                                Destination.Library -> LibraryScreen()
                                Destination.Settings -> SettingsScreen()
                            }
                        }

                        if (!wideLayout) {
                            NavigationBar(
                                containerColor = AppBackground,
                                contentColor = MainText,
                                windowInsets = WindowInsets.navigationBars
                            ) {
                                Destination.entries.forEachIndexed { index, destination ->
                                    NavigationBarItem(
                                        selected = selected == index,
                                        onClick = { selected = index },
                                        icon = { DestinationIcon(index) },
                                        label = { Text(destination.label, maxLines = 1) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OctoMark()
        Spacer(Modifier.width(11.dp))
        Column {
            Text("OctoPipe", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = MainText)
            Text("Your video space", fontSize = 11.sp, color = MutedText)
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(SurfaceColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.VideoLibrary, contentDescription = "Video library", tint = Accent)
        }
    }
}

@Composable
private fun OctoMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(38.dp)
            .background(Accent.copy(alpha = 0.14f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.VideoLibrary,
            contentDescription = "OctoPipe",
            tint = Accent,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun DestinationIcon(index: Int) {
    val icon = when (index) {
        0 -> Icons.Outlined.Home
        1 -> Icons.Outlined.Explore
        2 -> Icons.Outlined.Bookmarks
        else -> Icons.Outlined.Settings
    }
    Icon(icon, contentDescription = Destination.entries[index].label)
}

@Composable
private fun HomeScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    onExplore: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        item {
            Text("Discover", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MainText)
            Text("Find something worth watching.", color = MutedText, fontSize = 14.sp)
        }
        item {
            SearchField(query, onQueryChange, onExplore)
        }
        item {
            SectionHeading("Start exploring", "Search videos and channels")
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(SurfaceColor, RoundedCornerShape(20.dp))
                    .clickable(onClick = onExplore)
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = Accent, modifier = Modifier.size(30.dp))
                    Text("Search across supported services", color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        "Connectors are being built. Search results will appear here when a provider is enabled.",
                        color = MutedText,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        }
        item {
            SectionHeading("Your space", "Saved locally on this device")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoTile("Subscriptions", "Follow channels", Modifier.weight(1f))
                InfoTile("Downloads", "Coming next", Modifier.weight(1f))
            }
        }
        item {
            Text(
                "OctoPipe is an independent project. No account is required for this preview.",
                color = MutedText,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ExploreScreen(query: String, onQueryChange: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Explore", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MainText)
        SearchField(query, onQueryChange) {}
        Box(
            Modifier
                .fillMaxWidth()
                .background(SurfaceColor, RoundedCornerShape(18.dp))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Explore, contentDescription = null, tint = Accent, modifier = Modifier.size(28.dp))
                Text("Provider integration is the next milestone", color = MainText, fontWeight = FontWeight.SemiBold)
                Text(
                    "The app shell is ready. Video search will be enabled after the first service connector is implemented and tested.",
                    color = MutedText,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
private fun LibraryScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Library", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MainText)
        InfoTile("Subscriptions", "Your followed channels will live here", Modifier.fillMaxWidth())
        InfoTile("Downloads", "Offline video and audio", Modifier.fillMaxWidth())
        InfoTile("History", "Recently watched videos", Modifier.fillMaxWidth())
        InfoTile("Playlists", "Your personal collections", Modifier.fillMaxWidth())
    }
}

@Composable
private fun SettingsScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MainText)
        InfoTile("Appearance", "Dark Minimal · Always dark", Modifier.fillMaxWidth())
        InfoTile("Playback", "Quality, captions and playback behavior", Modifier.fillMaxWidth())
        InfoTile("Downloads", "Storage location and download preferences", Modifier.fillMaxWidth())
        InfoTile("Services", "Manage supported video providers", Modifier.fillMaxWidth())
        Text("OctoPipe 0.1.0 · Early development preview", color = MutedText, fontSize = 12.sp)
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceElevated, RoundedCornerShape(16.dp))
            .clickable(onClick = onSubmit)
            .padding(horizontal = 15.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = MutedText)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            cursorBrush = SolidColor(Accent),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MainText),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text("Search videos or channels", color = MutedText, fontSize = 14.sp)
                }
                inner()
            }
        )
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text(subtitle, color = MutedText, fontSize = 12.sp)
    }
}

@Composable
private fun InfoTile(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(SurfaceColor, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(title, color = MainText, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MutedText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

