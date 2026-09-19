package tv.nakash.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.tv.material3.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.nakash.data.local.*
import tv.nakash.data.repo.*
import tv.nakash.player.*
import tv.nakash.ui.library.*
import tv.nakash.ui.theme.NakashColors
import javax.inject.Inject

@Composable fun MyListScreen(nav: NavHostController) = LibraryScreen(nav,"mylist")
@Composable fun SearchScreen(nav: NavHostController) = tv.nakash.ui.search.TvSearchScreen(nav)

@HiltViewModel
class SettingsViewModel @Inject constructor(private val account: AccountStore, private val catalog: CatalogRepository, private val epg: EpgRepository, private val user: UserRepository, private val player: PlayerController, private val preview: PreviewPlayer, private val db: NakashDb, val controls:PlaybackPreferences) : ViewModel() {
    val status=MutableStateFlow<String?>(null)
    val busy=MutableStateFlow(false)
    fun refresh() = viewModelScope.launch {
        if(busy.value) return@launch
        busy.value=true
        var failures=0
        listOf<suspend ()->Unit>({status.value="מעדכנים ערוצים…";catalog.syncLive()},{status.value="מעדכנים סרטים…";catalog.syncVod()},{status.value="מעדכנים סדרות…";catalog.syncSeries()},{status.value="מעדכנים לוח שידורים…";epg.syncXmltv()}).forEach { task -> runCatching { task() }.onFailure { failures++ } }
        status.value=if(failures==0) "הספריות ולוח השידורים עודכנו" else "חלק מהתכנים לא עודכנו. בדוק את החיבור ונסה שוב."
        busy.value=false
    }
    fun restoreChannels() = viewModelScope.launch { user.favorites().first().filter { it.kind=="hidden_channel" }.forEach { user.setFavorite(it.kind,it.refId,false) }; status.value="כל הערוצים מוצגים שוב" }
    fun logout()=viewModelScope.launch {
        player.stop();preview.stop()
        withContext(Dispatchers.IO) { db.clearAllTables() }
        account.clear()
    }
}
/**
 * Netflix-style settings (item 5): two columns — sections on the right (RTL start), rows on the left.
 * Rows are quiet text lines with a value; focus is a white row, not a button. No color-button jargon:
 * a remote key is bound by pressing it ("learn"), and shows under the name Android reports for it.
 */
@Composable
fun SettingsScreen(vm:SettingsViewModel=hiltViewModel()) {
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val controls by vm.controls.state.collectAsState()
    var section by remember {mutableStateOf(0)}
    var confirm by remember {mutableStateOf(false)}
    var editingSeek by remember {mutableStateOf(false)}
    var learning by remember {mutableStateOf(false)}          // waiting for a physical key press
    var learnedKey by remember {mutableStateOf<Int?>(null)}   // key captured, now choose action
    var editing by remember {mutableStateOf<RemoteBinding?>(null)}
    val sections=listOf("כללי","שלט","נגן","חשבון")

    Row(Modifier.fillMaxSize().padding(start=24.dp,end=40.dp,top=36.dp,bottom=24.dp)) {
        Column(Modifier.width(260.dp).padding(end=32.dp)) {
            Text("הגדרות",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(bottom=22.dp,start=14.dp))
            sections.forEachIndexed {i,name -> SectionTab(name,section==i) {section=i} }
        }
        Column(Modifier.weight(1f).padding(top=58.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            when(section) {
                0 -> {
                    SettingRow("רענון כל התכנים", if(busy) "מעדכנים…" else "ערוצים, סרטים, סדרות ולוח שידורים", enabled=!busy) {vm.refresh()}
                    SettingRow("הצג שוב ערוצים שהוסתרו","") {vm.restoreChannels()}
                    status?.let {Text(it,color=NakashColors.Muted,modifier=Modifier.padding(start=16.dp,top=12.dp))}
                }
                1 -> {
                    Text("כפתורים בשלט",style=MaterialTheme.typography.titleLarge,color=NakashColors.Muted,modifier=Modifier.padding(start=16.dp,bottom=8.dp))
                    controls.bindings.forEach {b -> SettingRow(b.name,b.action.label) {editing=b} }
                    SettingRow("＋ הוסף כפתור","לחץ על כפתור בשלט והגדר מה הוא עושה בנגן") {learning=true}
                    Spacer(Modifier.height(18.dp))
                    SettingRow("איפוס לברירת המחדל","") {vm.controls.reset()}
                    Text("חצים, OK, חזרה, בית וספרות שמורים לניווט ואי אפשר להקצות אותם.",color=NakashColors.Dim,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(start=16.dp,top=14.dp))
                }
                2 -> {
                    SettingRow("גודל דילוג","${controls.seekSeconds} שניות") {editingSeek=true}
                    SettingRow("OK בשידור חי", if(controls.liveOkPauses) "ניגון / השהיה" else "לוח שידורים מקוצר") {vm.controls.setLiveOk(!controls.liveOkPauses)}
                    SettingRow("יחס תמונה", when(controls.aspectMode) {4->"מילוי · עם חיתוך";3->"מתיחה למסך";else->"מקורי · ללא חיתוך"}) {vm.controls.setAspect(when(controls.aspectMode) {0->4;4->3;else->0})}
                    Text("בנגן: שמאל אחורה, ימין קדימה, OK להשהיה. בשידור חי: מעלה/מטה להחלפת ערוץ, ימין למועדפים.",color=NakashColors.Dim,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(start=16.dp,top=14.dp))
                }
                3 -> {
                    SettingRow("התנתקות","פרטי הכניסה, הרשימה והיסטוריית הצפייה במכשיר יימחקו",enabled=!busy) {confirm=true}
                    Text("NakashTV 0.2 · לצפייה על המסך הגדול",color=NakashColors.Dim,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(start=16.dp,top=14.dp))
                }
            }
        }
    }

    if(editingSeek) Dialog(onDismissRequest={editingSeek=false}) {Surface {Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("גודל דילוג קדימה ואחורה",style=MaterialTheme.typography.titleLarge)
        PlaybackPreferences.SEEK_OPTIONS.forEach {seconds -> Action("${if(controls.seekSeconds==seconds) "✓ " else ""}$seconds שניות",{vm.controls.setSeek(seconds);editingSeek=false})}
    }}}

    // Learn mode: any non-reserved key closes the dialog and moves on to the action picker.
    if(learning) Dialog(onDismissRequest={learning=false}) {
        val req=remember {androidx.compose.ui.focus.FocusRequester()}
        LaunchedEffect(Unit) {kotlinx.coroutines.delay(80);runCatching {req.requestFocus()}}
        Surface(Modifier.focusRequester(req).focusable().onPreviewKeyEvent {e ->
            if(e.type!=androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val code=e.nativeKeyEvent.keyCode
            if(code==android.view.KeyEvent.KEYCODE_BACK) {learning=false;return@onPreviewKeyEvent true}
            if(code in PlaybackPreferences.RESERVED) return@onPreviewKeyEvent true
            learnedKey=code;learning=false;true
        }) {Column(Modifier.padding(32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("לחץ עכשיו על הכפתור בשלט",style=MaterialTheme.typography.headlineMedium)
            Text("כל כפתור שאינו חץ, OK, חזרה או בית. חזרה מבטלת.",color=NakashColors.Muted)
        }}
    }
    val pickFor=learnedKey?.let {RemoteBinding(it,PlaybackPreferences.keyName(it),controls.action(it))} ?: editing
    pickFor?.let {b -> Dialog(onDismissRequest={learnedKey=null;editing=null}) {Surface {Column(Modifier.padding(24.dp).width(420.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("מה יעשה הכפתור „${b.name}“?",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(bottom=8.dp))
        RemoteAction.entries.filter {it!=RemoteAction.NONE}.forEach {action -> SettingRow(action.label,if(b.action==action) "✓" else "") {vm.controls.bind(b.keyCode,action,b.name);learnedKey=null;editing=null}}
        if(editing!=null) SettingRow("הסר את הכפתור","") {vm.controls.unbind(b.keyCode);editing=null}
    }}}}

    if(confirm) Dialog(onDismissRequest={confirm=false}) { Surface { Column(Modifier.padding(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("להתנתק מהחשבון?",style=MaterialTheme.typography.titleLarge)
        Text("פרטי הכניסה, הרשימה והיסטוריית הצפייה במכשיר הזה יימחקו.")
        Action("ביטול",{confirm=false})
        Action("התנתקות",{confirm=false;vm.logout()})
    } } }
}

@Composable
private fun SectionTab(label:String,active:Boolean,click:()->Unit) {
    Surface(onClick=click,modifier=Modifier.fillMaxWidth().height(48.dp).padding(vertical=2.dp),
        shape=ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1f),
        colors=ClickableSurfaceDefaults.colors(containerColor=if(active) NakashColors.S1 else androidx.compose.ui.graphics.Color.Transparent,focusedContainerColor=androidx.compose.ui.graphics.Color.White,
            contentColor=if(active) NakashColors.Text else NakashColors.Muted,focusedContentColor=androidx.compose.ui.graphics.Color.Black)) {
        Box(Modifier.fillMaxSize().padding(horizontal=14.dp),contentAlignment=Alignment.CenterStart) {Text(label,style=MaterialTheme.typography.titleLarge)}
    }
}

/** A settings row: title on the start side, current value dimmed on the end side; focus = white row. */
@Composable
fun SettingRow(title:String,value:String,enabled:Boolean=true,click:()->Unit) {
    Surface(onClick=click,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),
        shape=ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1f),
        colors=ClickableSurfaceDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent,focusedContainerColor=androidx.compose.ui.graphics.Color.White,
            contentColor=NakashColors.Text,focusedContentColor=androidx.compose.ui.graphics.Color.Black,disabledContainerColor=androidx.compose.ui.graphics.Color.Transparent,disabledContentColor=NakashColors.Dim)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            if(value.isNotEmpty()) Text(value,style=MaterialTheme.typography.labelLarge,color=NakashColors.Muted,maxLines=1,modifier=Modifier.padding(start=16.dp))
        }
    }
}
