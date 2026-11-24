package com.dhatech.roku.ui.components

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import com.dhatech.roku.airplay.discovery.AirPlayReceiver

/**
 * The receiver rows, rendered identically wherever a receiver list appears: the
 * main screen and the Quick Settings picker both call this inside their own
 * LazyColumn, so the two entry points cannot drift apart in behaviour or looks.
 *
 * @param pairedIds stableIds with stored credentials; drives the "paired" badge.
 * @param enabled false while a session is mid-flight — every row turns inert.
 * @param onSelect tap action.
 * @param onLongPress long-press action; only receivers that also advertise an
 *   audio-only service get it (they are the ones with casting-mode choices).
 * @param showAvatar false in the Quick Settings picker, where the rows stay
 *   compact without the leading avatar.
 */
@Suppress("LongParameterList")
fun LazyListScope.receiverItems(
    receivers: List<AirPlayReceiver>,
    pairedIds: Set<String>,
    enabled: Boolean,
    onSelect: (AirPlayReceiver) -> Unit,
    selectedEndpointKey: String? = null,
    onCastClick: ((AirPlayReceiver) -> Unit)? = null,
    onLongPress: ((AirPlayReceiver) -> Unit)? = null,
    showAvatar: Boolean = true,
) {
    items(
        items = receivers,
        // Two receivers can advertise the same `deviceid` (both 爱思投屏 and
        // UxPlay fake AppleTV3,2 with the same MAC), so key on the network
        // endpoint, which is unique.
        key = { it.endpointKey },
    ) { receiver ->
        val isSelected = receiver.endpointKey == selectedEndpointKey
        ReceiverCard(
            receiver = receiver,
            isPaired = receiver.stableId in pairedIds,
            // Mirroring-incapable receivers stay visible but inert, so the user
            // can tell they were found rather than missed. Audio-only devices
            // are tappable — they start an audio session instead.
            enabled = enabled && (receiver.supportsMirroring || receiver.isAudioOnly),
            isSelected = isSelected,
            onClick = { onSelect(receiver) },
            onCastClick = if (onCastClick != null && isSelected) {
                { onCastClick(receiver) }
            } else {
                null
            },
            onLongClick = if (onLongPress != null &&
                receiver.hasAudioOnlyService &&
                !receiver.isAudioOnly
            ) {
                { onLongPress(receiver) }
            } else {
                null
            },
            showAvatar = showAvatar,
        )
    }
}
