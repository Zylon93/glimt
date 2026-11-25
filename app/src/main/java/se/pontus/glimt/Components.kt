package se.pontus.glimt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

internal val Accent = Color(0xFF5CC8FF)
internal val Bg = Color(0xFF0D1016)
internal val SideBg = Color(0xFF12161D)
internal val CardBg = Color(0xFF1C2230)
internal val Muted = Color(0xFF8A93A3)
internal val TextBright = Color(0xFFE8ECF2)

/** Titel utan leverantörsprefix ("SE - ", "|EN|") och kvalitetstaggar. */
internal fun displayTitle(raw: String): String = runCatching { Subtitles.clean(raw).first }.getOrDefault(raw)

/** Ett fokuserbart element som förstoras och får en ram när fjärrkontrollen står på det. */
@Composable
internal fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    scale: Float = 1.06f,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        modifier
            .graphicsLayer {
                val s = if (focused) scale else 1f
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    ) { content(focused) }
}

/** Rundad knapp av den typ som finns i hero-bilden och på detaljsidorna. */
@Composable
internal fun PillButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50), scale = 1.08f) { focused ->
        Text(
            text,
            modifier = Modifier
                .background(
                    when {
                        focused -> Color.White
                        primary -> Accent
                        else -> Color.White.copy(alpha = 0.12f)
                    }
                )
                .padding(horizontal = 26.dp, vertical = 12.dp),
            color = if (focused || primary) Color.Black else Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun SectionTitle(text: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 10.dp)) {
        Text(text, color = TextBright, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (hint != null) {
            Spacer(Modifier.width(12.dp))
            Text(hint, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun Poster(url: String?, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.background(CardBg), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).size(300, 450).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(displayTitle(title), Modifier.padding(8.dp), textAlign = TextAlign.Center, color = Muted)
        }
    }
}

/** Stående omslag med titel under. */
@Composable
internal fun PosterCard(
    title: String,
    logo: String?,
    caption: String?,
    modifier: Modifier = Modifier,
    favorite: Boolean = false,
    onClick: () -> Unit,
) {
    Column(modifier) {
        FocusBox(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Box(Modifier.fillMaxSize()) {
                Poster(logo, title, Modifier.fillMaxSize())
                if (favorite) {
                    Text(
                        "♥",
                        color = Color(0xFFFF5A6A),
                        fontSize = 18.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = TextBright)
        if (caption != null) Text(caption, fontSize = 11.sp, color = Muted, maxLines = 1)
    }
}

/** Liggande kort med förloppsindikator för "Fortsätt titta". */
@Composable
internal fun ContinueCard(p: Progress, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val e = p.entry
    Column(modifier) {
        FocusBox(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize()) {
                Poster(e.logo, e.title, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                )
                Text(
                    "▶",
                    color = Color.White,
                    fontSize = 26.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.fraction).background(Accent))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val title = if (e.kind == Kind.EPISODE && e.series != null) displayTitle(e.series) else displayTitle(e.title)
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = TextBright)
        val sub = buildString {
            if (p.upNext) append("Nästa avsnitt · ")
            if (e.kind == Kind.EPISODE && e.season > 0) append("S%02d E%02d · ".format(e.season, e.episode))
            if (p.minutesLeft > 0) append("${p.minutesLeft} min kvar")
        }
        if (sub.isNotBlank()) Text(sub.trimEnd(' ', '·'), fontSize = 12.sp, color = Muted, maxLines = 1)
    }
}

@Composable
internal fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TextBright, textAlign = TextAlign.Center, fontSize = 18.sp)
    }
}

/** Låter fjärrkontrollens upp/ner flytta fokus ut ur textfält. */
@Composable
internal fun Modifier.dpadEscape(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}
