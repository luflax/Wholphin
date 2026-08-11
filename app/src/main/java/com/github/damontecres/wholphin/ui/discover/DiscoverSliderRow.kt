package com.github.damontecres.wholphin.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.CustomDiscoverSlider
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.cards.GenreCard
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.util.StringProvider

/**
 * A row of custom discover sliders configured on the Seerr server, one card per slider
 *
 * Sliders have no artwork of their own, so this reuses [GenreCard] with a null image, which paints a
 * background colour derived from the id and centres the title.
 */
@Composable
fun DiscoverSliderRow(
    title: StringProvider,
    sliders: List<CustomDiscoverSlider>,
    onClickItem: (Int, CustomDiscoverSlider) -> Unit,
    onCardFocus: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onLongClickItem: (Int, CustomDiscoverSlider) -> Unit = { _, _ -> },
) {
    if (sliders.isEmpty()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier,
        ) {
            Text(
                text = title.getString(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.loading),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    } else {
        ItemRow(
            title = title.getString(),
            items = sliders,
            onClickItem = onClickItem,
            onLongClickItem = onLongClickItem,
            cardContent = { index, item, mod, onClick, onLongClick ->
                GenreCard(
                    genreId = item?.id?.toUUID(),
                    name = item?.title,
                    imageUrl = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier =
                        mod
                            .height(Cards.heightEpisode)
                            .onFocusChanged {
                                if (it.isFocused) onCardFocus.invoke(index)
                            },
                )
            },
            modifier = modifier,
            horizontalPadding = 16.dp,
            showViewMore = false,
            viewMoreCardContent = {},
        )
    }
}
