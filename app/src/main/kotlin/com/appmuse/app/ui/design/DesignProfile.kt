// DESIGN ENGINE V2 GENERATED — selected profile only (playful).
package com.appmuse.app.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

object DesignProfileTokens {
    const val id = "playful"
    const val composition = "bento-playground"
    const val navigationStyle = "tabs"
    const val motionStyle = "playful"
    const val motionDurationMs = 340
    val cardRadius = 24.dp
    val cardPadding = 16.dp
    val navigationElevation = 5.dp
    const val alwaysShowNavigationLabels = true
}

@Composable
fun ProfileSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(DesignProfileTokens.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 5.dp,
        shadowElevation = 5.dp,
        border = null,
    ) {
        Column(modifier = Modifier.padding(DesignProfileTokens.cardPadding), content = content)
    }
}

@Composable
fun ProfileDecoration(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(width = 54.dp, height = 3.dp).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
fun ProfileHeroArtwork(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
        Box(Modifier.size(62.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape))
    }
}

@Composable
fun ProfileEditorialArtwork(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(96.dp).background(
            Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceVariant)),
            RoundedCornerShape(DesignProfileTokens.cardRadius),
        ),
    ) {
        Box(Modifier.align(Alignment.TopEnd).padding(16.dp).size(42.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape))
        Box(Modifier.align(Alignment.BottomStart).padding(18.dp).size(width = 112.dp, height = 4.dp).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
fun ProfileProductArtwork(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(78.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)))
        Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}

@Composable
fun ProfileHero(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    ProfileDecoration(Modifier.fillMaxWidth())
    ProfileHeroArtwork(Modifier.fillMaxWidth())
    
    
    Box(modifier = modifier.fillMaxWidth().heightIn(min = 190.dp).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer))).padding(24.dp)) { Box(Modifier.align(Alignment.TopEnd).size(76.dp).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f), CircleShape)); Column(Modifier.align(Alignment.BottomStart)) { Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer); if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer) } }
}

/** Recipe-owned collection geometry. Generated as one concrete native layout;
 * feature renderers supply data and interaction, never a generic profile bool. */
@Composable
fun ProfileCollection(itemCount: Int, modifier: Modifier = Modifier, itemContent: @Composable (Int) -> Unit) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { items(itemCount) { index -> Row { Box(Modifier.width(3.dp).heightIn(min = 64.dp).background(MaterialTheme.colorScheme.primary)); Spacer(Modifier.width(12.dp)); Box(Modifier.weight(1f)) { itemContent(index) } } } }
}

@Composable
fun ProfileDetailSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ProfileSurface(modifier.fillMaxWidth(), content)
}

@Composable
fun ProfileStateView(title: String, message: String, modifier: Modifier = Modifier) {
    ProfileSurface(modifier = modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
