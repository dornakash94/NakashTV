package tv.nakash.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
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
class SettingsViewModel @Inject constructor(private val account: AccountStore, private val catalog: CatalogRepository, private val epg: EpgRepository, private val user: UserRepository, private val player: PlayerController, private val preview: PreviewPlayer, private val db: NakashDb, val controls:PlaybackPreferences, val tmdbPrefs:TmdbPreferences, private val tmdb:tv.nakash.data.remote.TmdbRepository) : ViewModel() {
    val tmdbStatus=MutableStateFlow<String?>(null)
    fun saveTmdb(key:String,done:()->Unit)=viewModelScope.launch {
        val k=key.trim()
        if(k.isEmpty()) {tmdbStatus.value="הקלד מפתח";return@launch}
        tmdbStatus.value="בודקים את המפתח…"
        if(tmdb.validate(k)) {tmdbPrefs.set(k);tmdbStatus.value=null;done()} else tmdbStatus.value="TMDB לא אישר את המפתח. בדוק שהקלדת את ה־API Key (32 תווים)."
    }
    fun clearTmdb()=tmdbPrefs.set(null)
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
    var capturing by remember {mutableStateOf<RemoteAction?>(null)}   // waiting for the button for this action
    var managing by remember {mutableStateOf<RemoteAction?>(null)}    // an assigned action: change / remove
    var testing by remember {mutableStateOf(false)}                   // "which button is this?"
    var addingKey by remember {mutableStateOf(false)}                 // button first, then its action
    var pickFor by remember {mutableStateOf<Int?>(null)}
    var saved by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(saved) {if(saved!=null) {kotlinx.coroutines.delay(3_000);saved=null}}
    var editingTmdb by remember {mutableStateOf(false)}
    val tmdbKey by vm.tmdbPrefs.key.collectAsState()
    val sections=listOf("כללי","שלט רחוק","נגן","חשבון")

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
                    SettingRow("טריילרים ומידע מ־TMDB", if(tmdbKey!=null) "מחובר" else "הוספת מפתח API") {editingTmdb=true}
                    status?.let {Text(it,color=NakashColors.Muted,modifier=Modifier.padding(start=16.dp,top=12.dp))}
                }
                1 -> {
                    Text("בחר פעולה ולחץ על הכפתור שיבצע אותה, או הוסף כפתור ובחר לו פעולה.",color=NakashColors.Muted,style=MaterialTheme.typography.titleLarge.copy(fontSize=18.sp),modifier=Modifier.padding(start=16.dp,bottom=10.dp))
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f,false),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        item {SettingRow("＋ הוספת כפתור","לחץ על כפתור בשלט ואז בחר מה הוא יעשה") {addingKey=true}}
                        item {Spacer(Modifier.height(10.dp))}
                        items(REMOTE_ACTIONS.size) {i ->
                            val action=REMOTE_ACTIONS[i]
                            val keys=controls.bindings.filter {it.action==action}
                            SettingRow(action.label,if(keys.isEmpty()) "לא מוגדר" else keys.joinToString(" · ") {it.name}) {if(keys.isEmpty()) capturing=action else managing=action}
                        }
                        item {Spacer(Modifier.height(14.dp))}
                        item {SettingRow("בדיקת כפתור","לחץ על כפתור בשלט וראה איך הוא נקרא ומה הוא עושה") {testing=true}}
                        item {SettingRow("איפוס לברירת המחדל","") {vm.controls.reset();saved="הכפתורים חזרו להגדרות המקוריות"}}
                        item {Text("חצים, OK, חזרה, בית, עוצמת שמע וספרות שמורים לניווט. כפתורים כמו Netflix או YouTube בשלט מופעלים על ידי הטלוויזיה עצמה ולא מגיעים לאפליקציה.",
                            color=NakashColors.Dim,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(start=16.dp,top=14.dp,end=16.dp))}
                    }
                    saved?.let {Text("✓ $it",color=NakashColors.Ok,style=MaterialTheme.typography.titleLarge.copy(fontSize=18.sp),modifier=Modifier.padding(start=16.dp,top=10.dp))}
                }
                2 -> {
                    SettingRow("גודל דילוג","${controls.seekSeconds} שניות") {editingSeek=true}
                    SettingRow("OK בשידור חי", if(controls.liveOkPauses) "ניגון / השהיה" else "לוח שידורים מקוצר") {vm.controls.setLiveOk(!controls.liveOkPauses)}
                    SettingRow("יחס תמונה", when(controls.aspectMode) {4->"מילוי · עם חיתוך";3->"מתיחה למסך";else->"מקורי · ללא חיתוך"}) {vm.controls.setAspect(when(controls.aspectMode) {0->4;4->3;else->0})}
                    Text("בנגן: שמאל אחורה, ימין קדימה, OK להשהיה. בשידור חי: מעלה/מטה להחלפת ערוץ, ימין למועדפים.",color=NakashColors.Dim,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(start=16.dp,top=14.dp))
                }
                3 -> {
                    SettingRow("התנתקות","פרטי הכניסה, הרשימה והיסטוריית הצפייה במכשיר יימחקו",enabled=!busy) {confirm=true}
                    Text("NakashTV ${tv.nakash.BuildConfig.VERSION_NAME} · לצפייה על המסך הגדול",color=NakashColors.Dim,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(start=16.dp,top=14.dp))
                }
            }
        }
    }

    if(editingSeek) Dialog(onDismissRequest={editingSeek=false}) {Surface {Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("גודל דילוג קדימה ואחורה",style=MaterialTheme.typography.titleLarge)
        PlaybackPreferences.SEEK_OPTIONS.forEach {seconds -> Action("${if(controls.seekSeconds==seconds) "✓ " else ""}$seconds שניות",{vm.controls.setSeek(seconds);editingSeek=false})}
    }}}

    capturing?.let {action -> ButtonCaptureDialog(action.label,controls,{capturing=null}) {code ->
        vm.controls.assign(action,code);capturing=null;saved="${PlaybackPreferences.keyName(code)} ← ${action.label}"}}
    testing.takeIf {it}?.let {ButtonCaptureDialog(null,controls,{testing=false}) {}}
    if(addingKey) ButtonCaptureDialog("",controls,{addingKey=false}) {code -> addingKey=false;pickFor=code}
    pickFor?.let {code -> Dialog(onDismissRequest={pickFor=null}) {Surface {Column(Modifier.padding(28.dp).width(480.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        val current=controls.actions[code]
        Text("מה יעשה ${PlaybackPreferences.keyName(code)}?",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(bottom=10.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            items(REMOTE_ACTIONS.size) {i -> val a=REMOTE_ACTIONS[i]
                SettingRow(a.label,if(current==a) "✓ נוכחי" else "") {vm.controls.assign(a,code);pickFor=null;saved="${PlaybackPreferences.keyName(code)} ← ${a.label}"}}
            if(current!=null) item {SettingRow("ללא פעולה (הסרה)","") {vm.controls.unbind(code);pickFor=null;saved="הוסר: ${PlaybackPreferences.keyName(code)}"}}
        }
    }}}}
    managing?.let {action -> Dialog(onDismissRequest={managing=null}) {Surface {Column(Modifier.padding(28.dp).width(460.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(action.label,style=MaterialTheme.typography.headlineMedium)
        Text("כפתור נוכחי: "+controls.bindings.filter {it.action==action}.joinToString(" · ") {it.name},color=NakashColors.Muted)
        Spacer(Modifier.height(6.dp))
        Action("הגדרת כפתור אחר",{managing=null;capturing=action},Modifier.fillMaxWidth())
        Action("הסרת הכפתור",{vm.controls.clear(action);managing=null;saved="הוסר: ${action.label}"},Modifier.fillMaxWidth())
        Action("ביטול",{managing=null},Modifier.fillMaxWidth())
    }}}}

    if(editingTmdb) Dialog(onDismissRequest={editingTmdb=false}) { Surface { Column(Modifier.padding(28.dp).width(620.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        val tmdbStatus by vm.tmdbStatus.collectAsState()
        var draft by remember {mutableStateOf("")}
        Text("טריילרים ומידע מ־TMDB",style=MaterialTheme.typography.headlineMedium)
        Text("עם מפתח API אישי מ־TMDB, דפי הסרטים והסדרות מציגים טריילר רשמי, שחקנים וכותרים דומים מהספרייה שלך.",color=NakashColors.Muted)
        if(tmdbKey!=null) Text("המפתח מחובר.",color=NakashColors.Ok)
        tv.nakash.ui.library.SearchField(draft,{draft=it},if(tmdbKey!=null) "מפתח חדש (API Key)" else "API Key")
        tmdbStatus?.let {Text(it,color=NakashColors.Muted)}
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Action("שמירה",{vm.saveTmdb(draft) {editingTmdb=false}})
            if(tmdbKey!=null && !(vm.tmdbPrefs.hasBuiltIn && tmdbKey==tv.nakash.BuildConfig.TMDB_KEY)) Action(if(vm.tmdbPrefs.hasBuiltIn) "חזרה למפתח המובנה" else "הסרת המפתח",{vm.clearTmdb();editingTmdb=false})
            Action("ביטול",{editingTmdb=false})
        }
        Text("This product uses the TMDB API but is not endorsed or certified by TMDB.",color=NakashColors.Dim,style=MaterialTheme.typography.labelLarge)
    } } }

    if(confirm) Dialog(onDismissRequest={confirm=false}) { Surface { Column(Modifier.padding(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("להתנתק מהחשבון?",style=MaterialTheme.typography.titleLarge)
        Text("פרטי הכניסה, הרשימה והיסטוריית הצפייה במכשיר הזה יימחקו.")
        Action("ביטול",{confirm=false})
        Action("התנתקות",{confirm=false;vm.logout()})
    } } }
}

private val REMOTE_ACTIONS=listOf(RemoteAction.PLAY_PAUSE,RemoteAction.TRACKS,RemoteAction.START_OVER,RemoteAction.MINI_EPG,RemoteAction.FAVORITE,
    RemoteAction.SOURCE,RemoteAction.ASPECT,RemoteAction.CONTROLS,RemoteAction.MENU)

/**
 * Waits for a remote button. Keys are taken at the activity (KeyCapture), before focus, dialogs or the media
 * session, so every button that reaches the app is seen. Back cancels. In test mode (title null) it just shows
 * the name and current action of each button pressed.
 */
@Composable
private fun ButtonCaptureDialog(forAction:String?,controls:tv.nakash.data.local.PlaybackPreferencesState,close:()->Unit,onKey:(Int)->Unit) {
    var hint by remember {mutableStateOf<String?>(null)}
    var secondsLeft by remember {mutableStateOf(15)}
    DisposableEffect(Unit) {
        KeyCapture.onKey={code ->
            when {
                code==android.view.KeyEvent.KEYCODE_BACK -> {KeyCapture.onKey=null;close()}
                forAction==null -> {hint=PlaybackPreferences.keyName(code)+" · "+(controls.actions[code]?.label ?: "לא מוגדר");secondsLeft=15}
                code in PlaybackPreferences.RESERVED -> hint="${PlaybackPreferences.keyName(code)} משמש לניווט. נסה כפתור אחר."
                else -> {KeyCapture.onKey=null;onKey(code)}
            }
        }
        onDispose {KeyCapture.onKey=null}
    }
    LaunchedEffect(Unit) {while(secondsLeft>0) {kotlinx.coroutines.delay(1_000);secondsLeft--};KeyCapture.onKey=null;close()}
    // Drawn in the app's own window (not a Dialog): a Dialog is a separate window, and remote keys would go to it
    // instead of MainActivity, where KeyCapture listens.
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha=.72f)),contentAlignment=Alignment.Center) {Surface {Column(Modifier.padding(36.dp).width(560.dp),verticalArrangement=Arrangement.spacedBy(14.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(if(forAction!=null) "לחץ עכשיו על הכפתור בשלט" else "בדיקת כפתור",style=MaterialTheme.typography.headlineMedium)
        Text(when {forAction==null -> "לחץ על כפתור כלשהו בשלט"; forAction.isEmpty() -> "אחרי זה תבחר מה הוא יעשה"; else -> "הכפתור יבצע: $forAction"},color=NakashColors.Muted,style=MaterialTheme.typography.titleLarge)
        Box(Modifier.fillMaxWidth().height(64.dp).background(NakashColors.S2,androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
            Text(hint ?: "…",style=MaterialTheme.typography.titleLarge.copy(fontSize=22.sp),color=if(hint!=null && forAction!=null) NakashColors.Live else NakashColors.Text)
        }
        Text("חזרה לביטול · נסגר לבד בעוד $secondsLeft שניות",color=NakashColors.Dim,style=MaterialTheme.typography.labelLarge)
    }}}
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
