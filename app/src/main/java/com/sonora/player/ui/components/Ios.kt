package com.sonora.player.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L

/** iOS system tint colours for icon squares. */
object IosColors {
    val Red = Color(0xFFFF3B30)
    val Orange = Color(0xFFFF9500)
    val Yellow = Color(0xFFFFCC00)
    val Green = Color(0xFF34C759)
    val Teal = Color(0xFF30B0C7)
    val Blue = Color(0xFF007AFF)
    val Indigo = Color(0xFF5856D6)
    val Purple = Color(0xFFAF52DE)
    val Pink = Color(0xFFFF2D55)
    val Gray = Color(0xFF8E8E93)
}

class IosGroupScope {
    internal val rows = ArrayList<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) { rows += content }
}

/** iOS "inset grouped" list section: rounded card, hairline separators, small caps header and footer. */
@Composable
fun IosGroup(
    title: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    /** Home-style big section title instead of the small caps label. */
    largeTitle: Boolean = false,
    build: IosGroupScope.() -> Unit,
) {
    val scope = IosGroupScope().apply(build)
    if (scope.rows.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(top = if (largeTitle) 0.dp else 22.dp)) {
        if (title != null && largeTitle) {
            Text(
                title, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            )
        } else if (title != null) {
            Text(
                title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, bottom = 7.dp),
            )
        }
        Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
            scope.rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 58.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                r()
            }
        }
        if (footer != null) {
            Text(
                footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 7.dp),
            )
        }
    }
}

@Composable
fun IconSquare(@DrawableRes icon: Int, color: Color, size: Int = 30) {
    // Plain glyph in the secondary text colour: one quiet icon style everywhere instead of a rainbow of tiles.
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        LIcon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = (size * 0.74f).dp)
    }
}

/** A tappable iOS list row with a coloured icon square, optional detail text and a chevron. */
@Composable
fun IosRow(
    title: String,
    @DrawableRes icon: Int,
    color: Color,
    subtitle: String? = null,
    detail: String? = null,
    chevron: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconSquare(icon, color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.width(6.dp))
        }
        trailing?.invoke()
        if (chevron && trailing == null && onClick != null) {
            LIcon(L.drawable.lucide_ic_chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), size = 18.dp)
        }
    }
}

/** iOS row with a switch. */
@Composable
fun IosToggleRow(title: String, @DrawableRes icon: Int, color: Color, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    IosRow(
        title, icon, color, subtitle = subtitle, chevron = false,
        trailing = {
            Switch(
                checked = checked, onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = Color.White,
                    uncheckedThumbColor = Color.White, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        },
        onClick = { onChange(!checked) },
    )
}

/** iOS large title used at the top of each tab. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}
