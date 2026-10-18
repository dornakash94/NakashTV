package tv.nakash.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.nakash.data.update.AppUpdater
import tv.nakash.data.update.UpdateState
import tv.nakash.ui.library.Action
import tv.nakash.ui.theme.NakashColors

/** "גרסה חדשה זמינה": offers the release, then shows the download and hands it to Android's installer. */
@Composable
fun UpdateDialog(updater: AppUpdater) {
    val state by updater.state.collectAsState()
    // Back from Android's settings with the switch on: the update carries on without another press.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) updater.resumeIfPermitted() }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val held by updater.holdPrompt.collectAsState()
    if (state is UpdateState.None || held) return
    val release = when (val s = state) {
        is UpdateState.Available -> s.release; is UpdateState.NeedsPermission -> s.release; is UpdateState.Downloading -> s.release
        is UpdateState.Installing -> s.release; is UpdateState.Failed -> s.release; else -> return
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(state::class) { delay(120); runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = { if (!release.mandatory && state is UpdateState.Available) updater.later() },
        properties = DialogProperties(dismissOnBackPress = !release.mandatory, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(30.dp).width(520.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("גרסה חדשה ${release.versionName} זמינה", style = MaterialTheme.typography.headlineSmall)
                when (val s = state) {
                    is UpdateState.Available -> {
                        if (release.notes.isNotBlank()) Text(release.notes, color = NakashColors.Muted, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp))
                        Text("העדכון שומר את ההתחברות, הפרופילים וההיסטוריה.", color = NakashColors.Dim, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Action("עדכון עכשיו", { updater.install() }, Modifier.focusRequester(first))
                            if (!release.mandatory) Action("אחר כך", { updater.later() })
                        }
                    }
                    is UpdateState.NeedsPermission -> {
                        val text = if (s.noScreen)
                            "המכשיר הזה לא מאפשר לאשר עדכונים מתוך האפליקציה. אפשר לעדכן דרך Downloader עם הקוד 4033247."
                        else
                            "כדי שהאפליקציה תוכל להתעדכן, Android צריך אישור חד-פעמי: במסך שנפתח מדליקים את NakashTV (\"התקנת אפליקציות לא מוכרות\") ולוחצים חזור. העדכון ימשיך לבד."
                        Text(text, color = NakashColors.Muted, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!s.noScreen) Action("פתיחת ההגדרות שוב", { updater.install() }, Modifier.focusRequester(first))
                            if (!release.mandatory) Action("אחר כך", { updater.later() }, if (s.noScreen) Modifier.focusRequester(first) else Modifier)
                        }
                    }
                    is UpdateState.Downloading -> {
                        Text("מורידים… ${s.percent}%", color = NakashColors.Muted)
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .15f))) {
                            Box(Modifier.fillMaxWidth(s.percent / 100f).fillMaxHeight().background(NakashColors.Accent))
                        }
                    }
                    is UpdateState.Installing -> Text("פותחים את מסך ההתקנה… לוחצים \"עדכון\" כדי לסיים.", color = NakashColors.Muted)
                    is UpdateState.Failed -> {
                        Text(s.message, color = NakashColors.Live)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Action("ניסיון נוסף", { updater.install() }, Modifier.focusRequester(first))
                            if (!release.mandatory) Action("אחר כך", { updater.later() })
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}
