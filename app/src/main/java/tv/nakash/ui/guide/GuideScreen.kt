package tv.nakash.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.tv.material3.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import tv.nakash.data.local.*
import tv.nakash.data.repo.*
import tv.nakash.domain.PlaybackPolicy
import tv.nakash.player.*
import tv.nakash.ui.components.ChannelLogo
import tv.nakash.ui.theme.NakashColors
import tv.nakash.util.fmtTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class GuideViewModel @Inject constructor(val catalog:CatalogRepository,val epg:EpgRepository,val player:PlayerController):ViewModel() {
    val channels=catalog.channels().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val busy=MutableStateFlow(false)
    val status=MutableStateFlow<String?>(null)
    fun refresh()=viewModelScope.launch {
        if(busy.value) return@launch
        busy.value=true;status.value=null
        try {epg.syncXmltv()} catch(cancel:kotlinx.coroutines.CancellationException) {throw cancel}
        catch(_:Exception) {status.value="לא הצלחנו לעדכן את הלוח. המידע שכבר נשמר עדיין זמין."}
        finally {busy.value=false}
    }
}

@Composable
fun GuideScreen(nav:NavHostController,vm:GuideViewModel=hiltViewModel()) {
    val channels by vm.channels.collectAsState()
    val categories by remember {vm.catalog.liveCategories()}.collectAsState(emptyList())
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
    var selectedId by rememberSaveable {mutableStateOf<Int?>(null)}
    var category by rememberSaveable {mutableStateOf<Int?>(null)}
    var archiveOnly by rememberSaveable {mutableStateOf(false)}
    var filters by remember {mutableStateOf(false)}
    var day by rememberSaveable {mutableIntStateOf(0)}
    var dialog by remember {mutableStateOf<EpgEntity?>(null)}
    var focusedProgram by remember {mutableStateOf<EpgEntity?>(null)}
    var now by remember {mutableLongStateOf(System.currentTimeMillis()/1000)}
    LaunchedEffect(Unit) {while(true) {delay(30_000);now=System.currentTimeMillis()/1000}}
    val visible=remember(channels,category,archiveOnly) {channels.filter {c -> (!archiveOnly || c.archiveDays>0) && (category==null || category.toString() in c.categoryIds.split(','))}}
    val selected=visible.firstOrNull {it.id==selectedId} ?: visible.firstOrNull()
    val zone=ZoneId.systemDefault()
    val date=LocalDate.now(zone).plusDays(day.toLong())
    val start=date.atStartOfDay(zone).toEpochSecond()
    val end=date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val rows by remember(selected?.epgChannelId,start) {selected?.epgChannelId?.let {vm.epg.range(it,start,end)} ?: flowOf(emptyList())}.collectAsState(emptyList())
    val list=rememberLazyListState()
    val programFocus=remember {FocusRequester()}
    val channelFocus=remember {FocusRequester()}
    val scope=rememberCoroutineScope()
    val retention=selected?.archiveDays ?: 0
    val oldest=retention.coerceAtLeast(7)
    LaunchedEffect(selected?.id) {if(day < -oldest) day=-oldest;focusedProgram=null}
    LaunchedEffect(selected?.id,day,rows.isNotEmpty()) {
        val index=if(day==0) rows.indexOfFirst {it.end>now}.coerceAtLeast(0) else 0
        if(rows.isNotEmpty()) {list.scrollToItem(index);focusedProgram=rows[index]}
    }
    LaunchedEffect(visible.isNotEmpty()) {if(visible.isNotEmpty()) {delay(200);runCatching {channelFocus.requestFocus()}}}
    fun jump(hour:Int) {scope.launch {val target=date.atTime(hour,0).atZone(zone).toEpochSecond();val i=rows.indexOfFirst {it.end>target};if(i>=0) {list.scrollToItem(i);focusedProgram=rows[i];delay(80);runCatching {programFocus.requestFocus()}}}}
    fun live(c:ChannelEntity) {vm.player.zapChannels.value=visible;vm.player.play(PlayRequest.Live(c));nav.navigate("player")}
    fun archive(c:ChannelEntity,p:EpgEntity,offset:Long) {vm.player.play(PlayRequest.Archive(c,p,offset));dialog=null;nav.navigate("player")}
    Column(Modifier.fillMaxSize().padding(start=32.dp,end=32.dp,top=18.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        status?.let {Text(it,color=NakashColors.Muted,style=MaterialTheme.typography.bodySmall)}
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            LazyColumn(Modifier.width(190.dp).fillMaxHeight().focusRequester(channelFocus),contentPadding=PaddingValues(4.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                items(visible,key={it.id}) {c ->
                    Surface(onClick={selectedId=c.id;scope.launch {delay(100);runCatching {programFocus.requestFocus()}}},
                        modifier=Modifier.fillMaxWidth().height(60.dp).onFocusChanged {if(it.isFocused) selectedId=c.id},
                        shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1f),
                        colors=ClickableSurfaceDefaults.colors(containerColor=if(selected?.id==c.id) NakashColors.S2 else Color.Transparent,focusedContainerColor=NakashColors.S3)) {
                        Row(Modifier.fillMaxSize().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            if(selected?.id==c.id) Box(Modifier.width(3.dp).height(30.dp).background(NakashColors.Accent,RoundedCornerShape(2.dp)))
                            Box(Modifier.size(42.dp).background(Color.White.copy(alpha=.06f),RoundedCornerShape(9.dp)),contentAlignment=Alignment.Center){ChannelLogo(c,34,plain=true)}
                            Column(Modifier.weight(1f)) {Text(c.displayName,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium.copy(fontSize=14.sp));
                                if(c.archiveDays>0) Text("${c.archiveDays} ימי צפייה חוזרת",color=NakashColors.Muted,style=MaterialTheme.typography.labelSmall.copy(fontSize=10.sp))}
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Text(selected?.displayName ?: "בחרו ערוץ",style=MaterialTheme.typography.headlineMedium.copy(fontSize=26.sp),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        selected?.let {GuideButton("● לשידור החי",false,{live(it)})}
                        GuideButton("צפייה חוזרת",archiveOnly,{archiveOnly=!archiveOnly})
                        GuideButton(categories.firstOrNull {it.id==category}?.name ?: "כל הערוצים",category!=null,{filters=true})
                        GuideButton(if(busy) "מעדכנים…" else "עדכון",false,{vm.refresh()})
                    }
                }
                LazyRow(contentPadding=PaddingValues(3.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    items((0 downTo -oldest).toList()+listOf(1),key={it}) {offset ->
                        val d=LocalDate.now(zone).plusDays(offset.toLong())
                        val label=when(offset) {0->"היום";-1->"אתמול";1->"מחר";else->d.format(DateTimeFormatter.ofPattern("EEE dd.MM",Locale("he")))}
                        GuideButton(label,day==offset,{day=offset})
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("קפיצה לשעה",color=NakashColors.Muted,style=MaterialTheme.typography.bodySmall)
                    listOf(0,6,12,18).forEach {hour->GuideButton("%02d:00".format(hour),false,{jump(hour)})}
                    GuideButton("עכשיו",false,{day=0;jump(java.time.LocalTime.now().hour)})
                }
                if(rows.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(if(busy) "מעדכנים את לוח השידורים…" else "אין מידע ליום הזה",style=MaterialTheme.typography.titleLarge)
                    Text(if(retention==0 && day<0) "הערוץ לא מציע צפייה חוזרת" else "אפשר לבחור יום אחר או לעדכן את הלוח",color=NakashColors.Muted,style=MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp));GuideButton("עדכון לוח השידורים",false,{vm.refresh()})
                } else LazyColumn(Modifier.weight(1f).focusRequester(programFocus),state=list,contentPadding=PaddingValues(4.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(rows,key={it.id}) {p ->
                        val available=PlaybackPolicy.archiveAvailable(p.start,p.end,p.isFiller,retention,now)
                        val isLive=p.start<=now && p.end>now
                        val accent=if(isLive) NakashColors.Live else if(available) NakashColors.Accent else Color.Transparent
                        Surface(onClick={dialog=p},modifier=Modifier.fillMaxWidth().height(68.dp).onFocusChanged {if(it.isFocused) focusedProgram=p},
                            shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1f),
                            colors=ClickableSurfaceDefaults.colors(containerColor=if(isLive) NakashColors.S2 else NakashColors.S1,focusedContainerColor=NakashColors.S3),
                            border=ClickableSurfaceDefaults.border(focusedBorder=Border(androidx.compose.foundation.BorderStroke(2.dp,Color.White),shape=RoundedCornerShape(10.dp)))) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxHeight(0.6f).background(accent,RoundedCornerShape(2.dp)))
                                Row(Modifier.fillMaxSize().padding(start=18.dp,end=14.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                                    Column(Modifier.width(54.dp)) {Text(fmtTime(p.start),color=Color.White,style=MaterialTheme.typography.titleMedium.copy(fontSize=15.sp));Text(fmtTime(p.end),color=NakashColors.Muted,style=MaterialTheme.typography.bodySmall)}
                                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                        Text(p.title,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium.copy(fontSize=15.sp))
                                        val sub=p.description.takeIf {it.isNotBlank() && it!=p.title}
                                        if(sub!=null) Text(sub,color=NakashColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall.copy(fontSize=12.sp))
                                    }
                                    if(isLive) StateChip("● עכשיו",NakashColors.Live) else if(available) StateChip("צפייה חוזרת",NakashColors.Muted) else if(p.start>now) StateChip("בקרוב",NakashColors.Dim) else Unit
                                }
                                if(isLive && p.end>p.start) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha=.10f))) {
                                    Box(Modifier.fillMaxWidth(((now-p.start).toFloat()/(p.end-p.start)).coerceIn(0f,1f)).fillMaxHeight().background(NakashColors.Live))
                                }
                            }
                        }
                    }
                }
                Text(focusedProgram?.description?.takeIf {it.isNotBlank()} ?: "בחרו תוכנית לפרטים ולצפייה",color=NakashColors.Muted,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.height(38.dp),style=MaterialTheme.typography.bodyMedium.copy(fontSize=12.sp,lineHeight=18.sp))
            }
        }
    }
    if(filters) Dialog(onDismissRequest={filters=false}) {
        LazyColumn(Modifier.width(320.dp).heightIn(max=420.dp).background(NakashColors.S1,RoundedCornerShape(12.dp)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            item {GuideButton("כל הערוצים",category==null,{category=null;filters=false})}
            items(categories,key={it.id}) {c->GuideButton(c.name,category==c.id,{category=c.id;filters=false})}
        }
    }
    dialog?.let {p ->selected?.let {c ->ArchiveDetails(c,p,now,{dialog=null},{live(c)},{offset->archive(c,p,offset)})}}
}

@Composable
private fun StateChip(label:String,color:Color) {
    val text=if(color==NakashColors.Live) color else NakashColors.Muted
    Box(Modifier.background(color.copy(alpha=if(color==NakashColors.Live) .16f else .10f),RoundedCornerShape(7.dp)).padding(horizontal=10.dp,vertical=5.dp)) {
        Text(label,color=text,style=MaterialTheme.typography.labelSmall.copy(fontSize=11.sp),maxLines=1)
    }
}

@Composable
private fun GuideButton(label:String,selected:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    Surface(onClick=onClick,modifier=modifier.height(36.dp),shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        scale=ClickableSurfaceDefaults.scale(focusedScale=1f),colors=ClickableSurfaceDefaults.colors(containerColor=if(selected) Color.White.copy(alpha=.16f) else Color.Transparent,focusedContainerColor=Color.White,contentColor=Color.White,focusedContentColor=Color.Black)) {
        Box(Modifier.fillMaxHeight().padding(horizontal=13.dp),contentAlignment=Alignment.Center) {Text(label,style=MaterialTheme.typography.bodyMedium.copy(fontSize=14.sp))}
    }
}

@Composable
private fun ArchiveDetails(c:ChannelEntity,p:EpgEntity,now:Long,onDismiss:()->Unit,onLive:()->Unit,onArchive:(Long)->Unit) {
    val available=PlaybackPolicy.archiveAvailable(p.start,p.end,p.isFiller,c.archiveDays,now)
    val latest=PlaybackPolicy.archiveOffsetSeconds(p.start,p.end,now,Long.MAX_VALUE)
    var offset by remember(p.id) {mutableLongStateOf(0)}
    var seeking by remember {mutableStateOf(false)}
    val initial=remember {FocusRequester()}
    LaunchedEffect(Unit) {delay(100);runCatching {initial.requestFocus()}}
    Dialog(onDismissRequest=onDismiss) {
        Column(Modifier.width(620.dp).background(NakashColors.S1,RoundedCornerShape(12.dp)).padding(28.dp),verticalArrangement=Arrangement.spacedBy(13.dp)) {
            Text(c.displayName,color=NakashColors.Muted,style=MaterialTheme.typography.bodyMedium)
            Text(p.title,style=MaterialTheme.typography.headlineMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text("${fmtTime(p.start)} – ${fmtTime(p.end)}",color=NakashColors.Muted,style=MaterialTheme.typography.bodyMedium)
            if(p.description.isNotBlank()) Text(p.description,maxLines=3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
            if(available) {
                Text("בחרו מאיפה להתחיל",color=Color.White,style=MaterialTheme.typography.titleMedium)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(Modifier.fillMaxWidth().onFocusChanged {seeking=it.isFocused}.onPreviewKeyEvent {e ->
                        if(e.type!=KeyEventType.KeyDown) false else when(e.key) {
                            Key.DirectionLeft->{offset=(offset-60).coerceAtLeast(0);true}
                            Key.DirectionRight->{offset=(offset+60).coerceAtMost(latest);true}
                            Key.DirectionCenter,Key.Enter->{onArchive(offset);true}
                            else->false
                        }
                    }.focusable().padding(vertical=9.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.fillMaxWidth().height(if(seeking) 5.dp else 3.dp).background(Color.White.copy(alpha=.2f))) {Box(Modifier.fillMaxWidth(if(latest>0) offset.toFloat()/latest else 0f).fillMaxHeight().background(if(seeking) Color.White else NakashColors.Accent))}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(fmtTime(p.start+offset),color=Color.White);Text(fmtTime(p.start+latest),color=NakashColors.Muted)}
                    }
                }
                Text("בחצים: דקה אחורה או קדימה · לחיצה ממושכת לדילוג מהיר",color=NakashColors.Muted,style=MaterialTheme.typography.bodySmall)
            } else Text(if(p.start>now) "התוכנית עדיין לא שודרה" else "התוכנית אינה זמינה בארכיון של הערוץ",color=NakashColors.Muted)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                if(available) GuideButton(if(offset==0L) "▶ צפייה מהתחלה" else "▶ צפייה מ־${fmtTime(p.start+offset)}",true,{onArchive(offset)},Modifier.focusRequester(initial))
                GuideButton("לשידור החי",false,onLive,if(!available) Modifier.focusRequester(initial) else Modifier)
                GuideButton("חזרה",false,onDismiss)
            }
        }
    }
}
