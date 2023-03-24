package com.xayah.databackup.ui.activity.settings.components.clickable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.dimensionResource
import com.xayah.databackup.R
import com.xayah.databackup.ui.components.BodySmallText
import com.xayah.databackup.ui.components.TitleMediumText

@ExperimentalMaterial3Api
@Composable
fun Clickable(
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: String,
    onClick: () -> Unit,
) {
    val mediumPadding = dimensionResource(R.dimen.padding_medium)

    ListItem(
        modifier = Modifier
            .clip(RoundedCornerShape(mediumPadding))
            .clickable { onClick() },
        headlineContent = {
            TitleMediumText(text = title)
        },
        supportingContent = {
            BodySmallText(text = subtitle)
        },
        trailingContent = {
            TitleMediumText(text = content)
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        }
    )
}
