package tv.nakash.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.BackHandler
import tv.nakash.ui.library.Action
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import tv.nakash.player.PreviewPlayer
import tv.nakash.ui.components.ChannelLogo
import tv.nakash.ui.components.NetflixRow
import tv.nakash.ui.components.ProgressBar
import tv.nakash.util.fmtTime
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import tv.nakash.R
import tv.nakash.data.local.ChannelEntity
import tv.nakash.data.local.ChannelSourceEntity
import tv.nakash.data.local.EpgEntity
import tv.nakash.data.repo.CatalogRepository
import tv.nakash.data.repo.EpgRepository
import tv.nakash.data.repo.UserRepository
import tv.nakash.player.PlayRequest
import tv.nakash.player.PlayerController
import tv.nakash.ui.components.ChannelCard
import tv.nakash.ui.theme.NakashColors
import javax.inject.Inject

data class LiveShelf(val id:Int,val title:String,val channels:List<ChannelEntity>)

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val user: UserRepository,
    private val epg: EpgRepository,
    private val player: PlayerController,
    private val preview: PreviewPlayer,
) : ViewModel() {
    val categories = catalog.liveCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selected = MutableStateFlow<Int?>(null)
    val favorites = user.favorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val visibleChannels=combine(catalog.channels(),favorites) {all,prefs ->
        val hidden=prefs.filter {it.kind=="hidden_channel"}.map {it.refId}.toSet()
        all.filter {it.id.toString() !in hidden}
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val shelves=combine(visibleChannels,categories,favorites) {all,categories,prefs ->
        val favoriteIds=prefs.filter {it.kind=="channel"}.map {it.refId}.toSet()
        val byCategory=mutableMapOf<Int,MutableList<ChannelEntity>>()
        all.forEach {channel -> channel.categoryIds.split(',').mapNotNull {it.toIntOrNull()}.forEach {id ->
            val row=byCategory.getOrPut(id) {mutableListOf()};if(row.size<12) row.add(channel)
        }}
        buildList {
            val saved=all.filter {it.id.toString() in favoriteIds}.take(12)
            if(saved.isNotEmpty()) add(LiveShelf(-1,"המועדפים שלי",saved))
            categories.sortedWith(compareByDescending<tv.nakash.data.local.CategoryEntity> {it.pinned}.thenBy {it.order}).forEach {category ->
                byCategory[category.id]?.takeIf {it.isNotEmpty()}?.let {add(LiveShelf(category.id,category.name,it))}
            }
            if(all.isNotEmpty()) add(LiveShelf(0,"כל הערוצים",all.take(12)))
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val channels = combine(visibleChannels, favorites, selected) { all, prefs, category ->
        val hidden = prefs.filter { it.kind == "hidden_channel" }.map { it.refId }.toSet()
        val favs = prefs.filter { it.kind == "channel" }.map { it.refId }.toSet()
        all.filter { c -> c.id.toString() !in hidden && when (category) {
            null,0 -> true
            -1 -> c.id.toString() in favs
            else -> category.toString() in c.categoryIds.split(',')
        } }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val now = MutableStateFlow<Map<Int, EpgEntity?>>(emptyMap())
    val sources = MutableStateFlow<List<ChannelSourceEntity>>(emptyList())
    val focused=MutableStateFlow<ChannelEntity?>(null)
    val next=MutableStateFlow<EpgEntity?>(null)
    val previewPlayer get()=preview.player
    val previewFrame get()=preview.hasFrame
    private var focusJob:kotlinx.coroutines.Job?=null
    fun focus(c:ChannelEntity) {
        focused.value=c;next.value=null;preview.focus(c.id,this)
        focusJob?.cancel()
        focusJob=viewModelScope.launch {
            var pair=epg.nowAndNext(c.epgChannelId)
            // Written only when it changed: every write rebuilds the channel rows.
            now.update { if(it.containsKey(c.id) && it[c.id]==pair.first) it else it+(c.id to pair.first) };next.value=pair.second
            if(c.hasEpg && c.epgChannelId!=null && pair.first==null) {
                delay(350)
                epg.refreshShort(c.id,c.epgChannelId,c.displayName)
                pair=epg.nowAndNext(c.epgChannelId)
                now.update { it+(c.id to pair.first) };next.value=pair.second
            }
        }
    }
    fun warmVisible(list:List<ChannelEntity>)=viewModelScope.launch {
        val sample=list.take(90)
        val time=System.currentTimeMillis()/1000
        val rows=epg.gridRows(sample.mapNotNull { it.epgChannelId },time,time+1)
        val fresh=sample.associate { c -> c.id to rows[c.epgChannelId]?.firstOrNull()?.takeUnless { it.isFiller } }
        now.update { old -> if(fresh.all { (k,v) -> old.containsKey(k) && old[k]==v }) old else old+fresh }
    }
    fun stopPreview() { focusJob?.cancel();preview.stop(this) }
    fun context(c: ChannelEntity) = viewModelScope.launch { sources.value = catalog.sources(c.id) }
    fun favorite(c: ChannelEntity) = viewModelScope.launch { user.toggleFavorite("channel", c.id.toString()) }
    fun hide(c: ChannelEntity) = viewModelScope.launch { user.setFavorite("hidden_channel", c.id.toString(), true) }
    fun restoreHidden() = viewModelScope.launch {
        favorites.value.filter { it.kind == "hidden_channel" }.forEach { user.setFavorite(it.kind, it.refId, false) }
    }
    fun play(c: ChannelEntity, source: Int = 0,category:Int?=selected.value) {
        val favoriteIds=favorites.value.filter {it.kind=="channel"}.map {it.refId}.toSet()
        player.zapChannels.value = visibleChannels.value.filter {when(category) {null,0->true;-1->it.id.toString() in favoriteIds;else->category.toString() in it.categoryIds.split(',')}}
        player.play(PlayRequest.Live(c, source))
    }
}

@Composable
fun LiveScreen(nav: NavHostController, vm: LiveViewModel = hiltViewModel()) {
    val categories by vm.categories.collectAsState()
    val selected by vm.selected.collectAsState()
    val channels by vm.channels.collectAsState()
    val shelves by vm.shelves.collectAsState()
    val rowState=rememberSaveableStateHolder()
    val shelfScroll=rememberLazyListState()
    var focusedRow by remember {mutableIntStateOf(0)}
    LaunchedEffect(focusedRow) {runCatching {shelfScroll.animateScrollToItem(focusedRow)}}
    val ends=remember {mutableMapOf<Int,FocusRequester>()}
    var returning by remember {mutableStateOf<Int?>(null)}
    fun closeCategory() {returning=selected;vm.selected.value=null}
    BackHandler(selected!=null) {closeCategory()}
    val now by vm.now.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val sources by vm.sources.collectAsState()
    val focused by vm.focused.collectAsState()
    val next by vm.next.collectAsState()
    val gridFocus=remember { FocusRequester() }
    DisposableEffect(Unit) { onDispose { vm.stopPreview() } }
    LaunchedEffect(selected,channels.isNotEmpty(),shelves.isNotEmpty()) {
        vm.warmVisible(if(selected==null) shelves.take(8).flatMap {it.channels.take(10)} else channels)
        val first=if(selected==null) shelves.firstOrNull()?.channels?.firstOrNull() else channels.firstOrNull()
        if(first!=null) {vm.focus(first);delay(200);runCatching {(returning?.let {ends[it]} ?: gridFocus).requestFocus()};returning=null}
    }
    var contextCategory by remember {mutableStateOf<Int?>(null)}
    var context by remember { mutableStateOf<ChannelEntity?>(null) }
    var digits by remember { mutableStateOf("") }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(30000); clock = System.currentTimeMillis() / 1000 } }
    fun play(c: ChannelEntity, source: Int = 0,category:Int?=selected) { vm.play(c, source,category); nav.navigate("player") }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1000)
            channels.firstOrNull { it.number == digits.toIntOrNull() }?.let { play(it) }
            digits = ""
        }
    }
    val hasFrame by vm.previewFrame.collectAsState()
    if(selected==null) Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        val code=event.nativeKeyEvent.keyCode
        if(event.type==KeyEventType.KeyDown && code in 7..16) { digits=(digits+(code-7)).takeLast(4);true } else false
    }) {
        // The cards are built when the channels or their programmes change, not on every move of the focus; only the
        // focused card is replaced (with "next" added), so the rest keep their objects and skip recomposition.
        val baseRows=remember(shelves,now,clock) { shelves.map { shelf -> tv.nakash.ui.components.RowShelf("live${shelf.id}",shelf.title,cards=shelf.channels.map { c ->
            val p=now[c.id]
            tv.nakash.ui.components.RowCard("c${c.id}",tv.nakash.ui.components.CardKind.CHANNEL,c.displayName,channel=c,nowTitle=p?.title,
                meta=listOfNotNull("ערוץ ${c.number}",p?.let { "${fmtTime(it.start)}–${fmtTime(it.end)}" }).joinToString("  ·  "),plot=p?.description,
                progress=p?.takeIf { it.end>it.start }?.let { ((clock-it.start).toFloat()/(it.end-it.start)).coerceIn(0f,1f) },
                onFocus={vm.focus(c)},onClick={play(c,category=shelf.id)},onLongClick={context=c;contextCategory=shelf.id;vm.context(c)})
        },onShowAll={vm.selected.value=shelf.id}) } }
        val focusedKey=focused?.let { "c${it.id}" }
        val rows=remember(baseRows,focusedKey,next) {
            val n=next ?: return@remember baseRows
            baseRows.map { shelf -> if(shelf.cards.none { it.key==focusedKey }) shelf else shelf.copy(cards=shelf.cards.map { if(it.key==focusedKey) it.copy(meta=it.meta+"  ·  הבא: ${n.title}") else it }) }
        }
        tv.nakash.ui.components.NetflixRowsPage(rows,vm.previewPlayer,hasFrame,gridFocus,restoreKey="live")
        if(digits.isNotEmpty()) Text(digits,Modifier.align(Alignment.TopStart).padding(top=64.dp,start=24.dp).background(NakashColors.S1.copy(alpha=.92f),RoundedCornerShape(9.dp)).padding(horizontal=16.dp,vertical=7.dp),style=MaterialTheme.typography.titleLarge.copy(fontSize=20.sp))
    }
    else Column(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        val code=event.nativeKeyEvent.keyCode
        if(event.type==KeyEventType.KeyDown && code in 7..16) { digits=(digits+(code-7)).takeLast(4);true } else false
    }) {
        Box(Modifier.fillMaxWidth().height(300.dp).clipToBounds()) {
            if(focused!=null) AndroidView(factory={ctx -> (android.view.LayoutInflater.from(ctx).inflate(tv.nakash.R.layout.player_preview,null,false) as PlayerView).apply {
                useController=false;isFocusable=false;descendantFocusability=android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                player=vm.previewPlayer;resizeMode=androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }},modifier=Modifier.fillMaxSize(),onRelease={it.player=null})
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Transparent,.45f to NakashColors.Bg.copy(.25f),.75f to NakashColors.Bg.copy(.9f),1f to NakashColors.Bg)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent,.5f to Color.Transparent,.82f to NakashColors.Bg,1f to NakashColors.Bg)))
            Column(Modifier.align(Alignment.BottomStart).padding(start=32.dp,end=32.dp,bottom=18.dp).fillMaxWidth(.58f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("● שידור חי",color=NakashColors.Live,style=MaterialTheme.typography.labelLarge.copy(fontSize=13.sp))
                focused?.let { c ->
                    Text(c.displayName,style=MaterialTheme.typography.displayLarge.copy(fontSize=34.sp,lineHeight=38.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                    val current=now[c.id]
                    Text(current?.title ?: "שידור חי",style=MaterialTheme.typography.titleLarge.copy(fontSize=19.sp,lineHeight=24.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                    current?.let { p ->
                        Text("${fmtTime(p.start)}–${fmtTime(p.end)}${next?.let { "  ·  הבא: ${it.title}" } ?: ""}",color=NakashColors.Muted,style=MaterialTheme.typography.labelLarge.copy(fontSize=13.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(p.description,style=MaterialTheme.typography.bodyLarge.copy(fontSize=14.sp,lineHeight=19.sp),color=Color(0xFFD3D4D8),maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(p.end>p.start) Box(Modifier.width(420.dp).padding(top=4.dp)) { ProgressBar(((clock-p.start).toFloat()/(p.end-p.start)).coerceIn(0f,1f),NakashColors.Live,3) }
                    }
                }
            }
            if(digits.isNotEmpty()) Text(digits,Modifier.align(Alignment.TopEnd).padding(top=64.dp,end=24.dp).background(NakashColors.S1.copy(alpha=.92f),RoundedCornerShape(9.dp)).padding(horizontal=16.dp,vertical=7.dp),style=MaterialTheme.typography.titleLarge.copy(fontSize=20.sp))
            Text("OK לצפייה במסך מלא",Modifier.align(Alignment.BottomEnd).padding(horizontal=24.dp,vertical=12.dp),color=NakashColors.Dim,style=MaterialTheme.typography.labelLarge.copy(fontSize=11.sp))
        }
        if(selected!=null) tv.nakash.ui.components.CategoryHeading(
            if(selected==-1) "המועדפים שלי" else categories.firstOrNull {it.id==selected}?.name ?: "כל הערוצים",
            ::closeCategory,"${channels.size} ערוצים")
        if(selected==null) LazyColumn(Modifier.weight(1f).clipToBounds().focusGroup(),state=shelfScroll,contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            listItemsIndexed(shelves,key={_,sh->sh.id}) {shelfIndex,shelf ->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(shelf.title,Modifier.padding(horizontal=32.dp),style=MaterialTheme.typography.titleLarge.copy(fontSize=16.sp))
                    rowState.SaveableStateProvider(shelf.id) {
                        NetflixRow(contentPadding=PaddingValues(horizontal=32.dp,vertical=8.dp)) {
                            listItemsIndexed(shelf.channels,key={_,c->c.id}) {i,channel -> Box(Modifier.width(200.dp).then(if(shelfIndex==0&&i==0) Modifier.focusRequester(gridFocus).focusGroup() else Modifier)) {
                                LiveTile(channel,now[channel.id],clock,{focusedRow=shelfIndex;vm.focus(channel)},{play(channel,category=shelf.id)},{context=channel;contextCategory=shelf.id;vm.context(channel)})
                            }}
                            item(key="all") {
                                Surface(onClick={vm.selected.value=shelf.id},modifier=Modifier.width(200.dp).height(112.dp).focusRequester(ends.getOrPut(shelf.id) {FocusRequester()}),
                                    shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),colors=ClickableSurfaceDefaults.colors(containerColor=NakashColors.S2,focusedContainerColor=NakashColors.S3)) {
                                    Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                                        Text("←",style=MaterialTheme.typography.headlineMedium)
                                        Text("הצג הכול",style=MaterialTheme.typography.titleMedium)
                                        Text(shelf.title,color=NakashColors.Muted,style=MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else if(channels.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {Text("אין ערוצים בקטגוריה הזו",color=NakashColors.Muted)}
        else LazyVerticalGrid(columns=GridCells.Fixed(5),modifier=Modifier.weight(1f).padding(horizontal=22.dp).focusGroup(),contentPadding=PaddingValues(10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            gridItemsIndexed(channels,key={_,c->c.id}) {i,channel -> Box(if(i==0) Modifier.focusRequester(gridFocus).focusGroup() else Modifier) { LiveTile(channel,now[channel.id],clock,{vm.focus(channel)},{play(channel)},{context=channel;contextCategory=selected;vm.context(channel)}) }}
        }
    }

    context?.let { c ->
        Dialog(onDismissRequest = { context = null }) {
            Column(Modifier.width(440.dp).heightIn(max = 460.dp).background(NakashColors.S1).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(c.displayName, style = MaterialTheme.typography.headlineMedium)
                val fav = favorites.any { it.kind == "channel" && it.refId == c.id.toString() }
                Button(onClick = { vm.favorite(c); context = null }) { Text(stringResource(if (fav) R.string.remove_favorite else R.string.add_favorite)) }
                Button(onClick = { vm.hide(c); context = null }) { Text(stringResource(R.string.hide_channel)) }
                sources.forEachIndexed { i, src ->
                    val label = when (src.kind) {
                        "BACKUP" -> R.string.source_backup
                        "ACCESSIBLE" -> R.string.source_accessible
                        "RUSSIAN" -> R.string.source_russian
                        else -> R.string.source_primary
                    }
                    Button(onClick = { context = null; play(c, i,contextCategory) }) { Text("${stringResource(label)} · ${i + 1}") }
                }
                Button(onClick = { context = null }) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

@Composable
private fun LiveTile(c:ChannelEntity,program:EpgEntity?,now:Long,focus:()->Unit,play:()->Unit,menu:()->Unit) {
    var isFocused by remember {mutableStateOf(false)}
    Surface(onClick=play,onLongClick=menu,modifier=Modifier.fillMaxWidth().height(118.dp).onFocusChanged {isFocused=it.isFocused;if(it.isFocused) focus()},
        scale=ClickableSurfaceDefaults.scale(focusedScale=1.06f),
        shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors=ClickableSurfaceDefaults.colors(containerColor=Color(0xFF17181D),focusedContainerColor=Color(0xFF262932)),
        border=ClickableSurfaceDefaults.border(focusedBorder=androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp,Color.White),shape=RoundedCornerShape(10.dp)))) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(start=13.dp,end=13.dp,top=12.dp,bottom=14.dp),verticalArrangement=Arrangement.SpaceBetween) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha=.06f)),contentAlignment=Alignment.Center) { ChannelLogo(c,40,plain=true) }
                    Column(Modifier.weight(1f)) {
                        Text(c.displayName,color=Color.White,style=MaterialTheme.typography.titleLarge.copy(fontSize=15.sp,lineHeight=18.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("ערוץ ${c.number}",color=NakashColors.Dim,style=MaterialTheme.typography.labelMedium.copy(fontSize=11.sp),maxLines=1)
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(NakashColors.Live))
                    Text(program?.title ?: "שידור חי",color=if(isFocused) Color.White else NakashColors.Muted,style=MaterialTheme.typography.bodyMedium.copy(fontSize=12.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            if(program!=null && program.end>program.start) {
                val progress=((now-program.start).toFloat()/(program.end-program.start)).coerceIn(0f,1f)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha=.10f))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(NakashColors.Live))
                }
            }
        }
    }
}
