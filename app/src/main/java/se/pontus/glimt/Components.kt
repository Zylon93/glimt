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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.size
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

/** Titel utan leverantörsprefix ("SE - ", "|EN|") och kvalitetstaggar. */
internal fun displayTitle(raw: String): String = runCatching { Subtitles.clean(raw).first }.getOrDefault(raw)

/** Ett fokuserbart element som förstoras och får en ram när fjärrkontrollen står på det. */
@Composable
internal fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    scale: Float = 1.06f,
    glow: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val longFired = remember { booleanArrayOf(false) }
    // Håll inne OK: fjärrkontrollen skickar upprepade tryck, då körs onLongClick i stället för onClick
    val longPress = if (onLongClick == null) Modifier else Modifier.onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        when {
            !ok -> false
            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                if (!longFired[0]) {
                    longFired[0] = true
                    onLongClick()
                }
                true
            }
            e.type == KeyEventType.KeyUp && longFired[0] -> {
                longFired[0] = false
                true
            }
            else -> false
        }
    }
    Box(
        modifier
            .graphicsLayer {
                val s = if (focused) scale else 1f
                scaleX = s
                scaleY = s
            }
            .then(if (focused && glow) Modifier.shadow(22.dp, shape, ambientColor = Accent, spotColor = Accent) else Modifier)
            .clip(shape)
            .then(if (focused) Modifier.border(2.5.dp, Color.White, shape) else Modifier)
            .then(longPress)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    ) { content(focused) }
}

/** Rundad knapp (Spela, Mer info, Favorit …). */
@Composable
internal fun PillButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    FocusBox(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50), scale = 1.07f, glow = primary) { focused ->
        val fg = if (focused || primary) OnAccent else Color.White
        Row(
            Modifier
                .then(
                    when {
                        focused -> Modifier.background(Color.White)
                        primary -> Modifier.background(AccentBrush)
                        else -> Modifier.background(Color.White.copy(alpha = 0.12f))
                    }
                )
                .padding(start = if (icon != null) 20.dp else 26.dp, end = 26.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * Textfält i appens stil. På TV öppnas tangentbordet först när man trycker OK på fältet,
 * inte bara för att man passerar det med fjärrkontrollen. Klar/Tillbaka/upp/ner lämnar fältet.
 */
@Composable
internal fun GlimtTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    leading: ImageVector? = null,
    focusRequester: FocusRequester? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val boxFocus = focusRequester ?: remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val source = remember { MutableInteractionSource() }
    val boxFocused by source.collectIsFocusedAsState()
    val active = editing || boxFocused

    @Composable
    fun Decor(inner: @Composable () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (active) CardBg else Surface1, RoundedCornerShape(14.dp))
                .border(
                    if (active) 2.dp else 1.dp,
                    when {
                        editing -> Accent
                        boxFocused -> Color.White
                        else -> Color.White.copy(alpha = 0.08f)
                    },
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Icon(leading, contentDescription = null, tint = if (active) Accent else Muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, color = Muted, fontSize = 17.sp, maxLines = 1)
                inner()
            }
            if (boxFocused && !editing) {
                Text("OK = skriv", color = Muted, fontSize = 13.sp)
            }
        }
    }

    Column(modifier) {
        if (label != null) {
            Text(label, color = if (active) Accent else Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        if (editing) {
            var hadFocus by remember { mutableStateOf(false) }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(Accent),
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontFamily = Outfit),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    returnFocus = true
                    editing = false
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .onFocusChanged { f ->
                        if (f.isFocused) hadFocus = true
                        else if (hadFocus) editing = false
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.key == Key.Back && e.type == KeyEventType.KeyUp) {
                            returnFocus = true
                            editing = false
                            true
                        } else false
                    }
                    .dpadEscape(),
                decorationBox = { inner -> Decor(inner) },
            )
            LaunchedEffect(Unit) {
                runCatching { fieldFocus.requestFocus() }
                keyboard?.show()
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .focusRequester(boxFocus)
                    .clickable(interactionSource = source, indication = null) { editing = true }
            ) {
                Decor { Text(value, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            LaunchedEffect(returnFocus) {
                if (returnFocus) {
                    returnFocus = false
                    runCatching { boxFocus.requestFocus() }
                }
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 26.dp, bottom = 12.dp)) {
        Box(Modifier.width(4.dp).height(22.dp).background(AccentBrush, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextBright, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
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
    badges: List<String> = emptyList(),
    onClick: () -> Unit,
) {
    Column(modifier) {
        FocusBox(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Box(Modifier.fillMaxSize()) {
                Poster(logo, title, Modifier.fillMaxSize())
                if (badges.isNotEmpty()) {
                    Row(
                        Modifier.align(Alignment.TopStart).padding(7.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                    ) {
                        badges.forEach { b ->
                            Text(
                                b,
                                color = if (b == "SV") OnAccent else Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(if (b == "SV") Accent else Color.Black.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (favorite) {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = Bad,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                            .padding(5.dp)
                            .size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = TextBright, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
        if (caption != null) Text(caption, fontSize = 12.sp, color = Muted, maxLines = 1)
    }
}

/** Liggande kort med förloppsindikator för "Fortsätt titta". */
@Composable
internal fun ContinueCard(p: Progress, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val e = p.entry
    val context = LocalContext.current
    Column(modifier) {
        FocusBox(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            onLongClick = {
                Store.remove(p)
                android.widget.Toast.makeText(context, "Togs bort från Fortsätt titta", android.widget.Toast.LENGTH_SHORT).show()
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                Poster(e.logo, e.title, Modifier.fillMaxSize())
                if (p.isNew) {
                    Text(
                        "NYTT AVSNITT",
                        color = OnAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Accent, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                )
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(8.dp)
                        .size(30.dp),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.fraction).background(AccentBrush))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val title = if (e.kind == Kind.EPISODE && e.series != null) displayTitle(e.series) else displayTitle(e.title)
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = TextBright)
        val sub = buildString {
            if (p.isNew) append("Nytt avsnitt · ") else if (p.upNext) append("Nästa avsnitt · ")
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


/** Små märken på omslag: 4K och SV (svenskt tal eller svensk text enligt kategori/titel). */
internal fun entryBadges(e: Entry): List<String> = listOfNotNull(
    "4K".takeIf { is4k(e.title, e.group) },
    "SV".takeIf { isSwedish(e.title, e.group) },
)

internal fun seriesBadges(s: Series): List<String> = listOfNotNull(
    "4K".takeIf { is4k(s.name, s.group) },
    "SV".takeIf { isSwedish(s.name, s.group) },
)
