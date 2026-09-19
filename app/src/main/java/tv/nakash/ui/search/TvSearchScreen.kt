package tv.nakash.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.tv.material3.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import tv.nakash.data.local.ChannelEntity
import tv.nakash.data.repo.CatalogRepository
import tv.nakash.domain.SearchDocument
import tv.nakash.domain.SearchEngine
import tv.nakash.player.PlayRequest
import tv.nakash.player.PlayerController
import tv.nakash.ui.components.ChannelLogo
import tv.nakash.ui.components.PosterCard
import tv.nakash.ui.theme.NakashColors
import javax.inject.Inject

internal data class SearchTile(val document:SearchDocument,val image:String?,val year:Int?,val kind:String,val ref:String,val channel:ChannelEntity?=null)
private data class SearchCatalog(val tiles:Map<String,SearchTile> = emptyMap(),val documents:List<SearchDocument> = emptyList())
internal data class SearchResults(val items:List<SearchTile> = emptyList(),val suggestions:List<String> = emptyList(),val total:Int=0)
@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class TvSearchViewModel @Inject constructor(catalog:CatalogRepository,private val player:PlayerController,private val saved:SavedStateHandle):ViewModel() {
    val query=saved.getStateFlow("searchQuery","")
    fun edit(value:String) {saved["searchQuery"]=value.take(80)}
    private val index=combine(catalog.newestMovies(Int.MAX_VALUE),catalog.recentlyUpdatedSeries(Int.MAX_VALUE),catalog.channels()) { movies,series,channels ->
        val tiles=buildList {
            movies.forEach { m -> add(SearchTile(SearchDocument("m${m.id}",m.title,listOfNotNull(m.cast,m.director,m.genres).joinToString(" ")),m.poster,m.year,"סרט",m.id.toString())) }
            series.forEach { s -> add(SearchTile(SearchDocument("s${s.id}",s.title,listOfNotNull(s.cast,s.genres).joinToString(" ")),s.cover,s.year,"סדרה",s.id.toString())) }
            channels.forEach { c -> add(SearchTile(SearchDocument("c${c.id}",c.displayName,channelNumber=c.number),c.logo,null,"ערוץ",c.id.toString(),c)) }
        }
        val ordered=tv.nakash.domain.DiscoveryOrder.interleave(tiles.filter {it.kind=="סרט"},tiles.filter {it.kind=="סדרה"})+tiles.filter {it.channel!=null}
        SearchCatalog(ordered.associateBy {it.document.id},ordered.map {it.document})
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),SearchCatalog())
    internal val results=combine(index,query.debounce(120)) { data,q ->
        val matches=SearchEngine.search(data.documents,q)
        SearchResults(matches.items.mapNotNull {data.tiles[it.id]},matches.suggestions,matches.total)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),SearchResults())
    fun play(channel:ChannelEntity) {
        player.zapChannels.value=index.value.tiles.values.mapNotNull {it.channel}
        player.play(PlayRequest.Live(channel))
    }
}

@Composable
fun TvSearchScreen(nav:NavHostController,vm:TvSearchViewModel=hiltViewModel()) {
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    var language by rememberSaveable {mutableIntStateOf(0)}
    val firstKey=remember {FocusRequester()}
    val resultFocus=remember {FocusRequester()}
    LaunchedEffect(Unit) {delay(250);firstKey.requestFocus()}
    val characters=when(language) {0->"אבגדהוזחטיכלמנסעפצקרשתךםןףץ";1->"ABCDEFGHIJKLMNOPQRSTUVWXYZ";else->"1234567890"}.map {it.toString()}
    fun type(value:String) {vm.edit(query+value)}
    Row(Modifier.fillMaxSize().padding(horizontal=24.dp,vertical=24.dp).onPreviewKeyEvent {event ->
        if(event.type!=KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val native=event.nativeKeyEvent
        when {
            native.keyCode==android.view.KeyEvent.KEYCODE_DEL -> {vm.edit(query.dropLast(1));true}
            !native.isCtrlPressed && !native.isAltPressed && native.unicodeChar>=32 && native.unicodeChar!=127 -> {type(native.unicodeChar.toChar().toString());true}
            else -> false
        }
    },horizontalArrangement=Arrangement.spacedBy(28.dp)) {
        // RTL places the keyboard panel on the right, and results on the left.
        Column(Modifier.width(260.dp).fillMaxHeight()) {
            Text("מה בא לך לראות?",style=MaterialTheme.typography.headlineMedium.copy(fontSize=24.sp))
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(46.dp).background(NakashColors.S2,RoundedCornerShape(8.dp)).padding(horizontal=14.dp),contentAlignment=Alignment.CenterStart) {
                Text(query.ifEmpty {"סרט, סדרה, ערוץ או שחקן"},color=if(query.isBlank()) NakashColors.Muted else NakashColors.Text,style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().background(NakashColors.S1,RoundedCornerShape(12.dp)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    listOf("אבג","ABC","123").forEachIndexed {i,label -> SearchKey(label,{language=i},Modifier.weight(1f),selected=language==i)}
                }
                characters.chunked(6).forEachIndexed {row,letters ->
                    Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        letters.forEachIndexed {col,letter -> SearchKey(letter,{type(letter)},Modifier.weight(1f).then(if(row==0&&col==0) Modifier.focusRequester(firstKey) else Modifier)) }
                        repeat(6-letters.size) {Spacer(Modifier.weight(1f))}
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    SearchKey("⌫",{vm.edit(query.dropLast(1))},Modifier.weight(1f),description="מחיקת תו")
                    SearchKey("רווח",{type(" ")},Modifier.weight(2f))
                    SearchKey("נקה",{vm.edit("")},Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(if(query.isBlank()) "הקלד כדי לגלות משהו טוב" else "השלמות לחיפוש",color=NakashColors.Muted,style=MaterialTheme.typography.labelLarge.copy(fontSize=12.sp))
            LazyColumn(Modifier.fillMaxWidth().height(112.dp),contentPadding=PaddingValues(vertical=5.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                items(results.suggestions,key={it}) { suggestion -> SearchKey(suggestion,{vm.edit(suggestion)},Modifier.fillMaxWidth(),compact=true) }
                if(query.isNotBlank() && results.suggestions.isEmpty()) item {Text("נסה שם נוסף או כתיב אחר",color=NakashColors.Muted,style=MaterialTheme.typography.labelLarge.copy(fontSize=12.sp))}
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text(if(query.isBlank()) "יש מה לגלות" else "תוצאות החיפוש",style=MaterialTheme.typography.titleLarge.copy(fontSize=20.sp))
                if(query.isNotBlank()) Text("${results.total} תוצאות",color=NakashColors.Muted,style=MaterialTheme.typography.labelLarge.copy(fontSize=12.sp))
            }
            Spacer(Modifier.height(16.dp))
            if(query.isNotBlank() && results.items.isEmpty()) Column(Modifier.padding(top=80.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("לא מצאנו תוצאות ל־״$query״",style=MaterialTheme.typography.titleLarge)
                Text("אפשר לחפש גם לפי שחקן, ז׳אנר או מספר ערוץ",color=NakashColors.Muted,style=MaterialTheme.typography.bodyLarge.copy(fontSize=16.sp))
            }
            LazyVerticalGrid(GridCells.Adaptive(108.dp),Modifier.weight(1f).focusRequester(resultFocus),contentPadding=PaddingValues(8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                items(results.items,key={it.document.id}) {tile ->
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        val channel=tile.channel
                        if(channel!=null) Surface(onClick={vm.play(channel);nav.navigate("player")},modifier=Modifier.fillMaxWidth().height(150.dp),shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),colors=ClickableSurfaceDefaults.colors(containerColor=NakashColors.Tile,focusedContainerColor=NakashColors.S3)) {
                            Column(Modifier.fillMaxSize().padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                                ChannelLogo(channel,56)
                                Spacer(Modifier.height(14.dp))
                                Text(channel.displayName,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp))
                            }
                        } else PosterCard(tile.document.title,tile.year,tile.image,null,{}, {nav.navigate(if(tile.kind=="סרט") "movie/${tile.ref}" else "seriesDetail/${tile.ref}")},tile.document.title)
                        Text(tile.document.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelLarge.copy(fontSize=13.sp))
                        Text(listOfNotNull(tile.kind,tile.year?.toString()).joinToString(" · "),color=NakashColors.Muted,style=MaterialTheme.typography.labelLarge.copy(fontSize=11.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchKey(label:String,click:()->Unit,modifier:Modifier=Modifier,selected:Boolean=false,description:String?=null,compact:Boolean=false) {
    Surface(onClick=click,modifier=modifier.height(if(compact) 29.dp else 32.dp),
        shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
        scale=ClickableSurfaceDefaults.scale(focusedScale=1.06f),
        colors=ClickableSurfaceDefaults.colors(containerColor=if(selected) NakashColors.S3 else NakashColors.S2,focusedContainerColor=Color.White,contentColor=NakashColors.Text,focusedContentColor=Color.Black)) {
        Box(Modifier.fillMaxSize().padding(horizontal=6.dp),contentAlignment=if(compact) Alignment.CenterStart else Alignment.Center) {
            Text(label,style=MaterialTheme.typography.labelLarge.copy(fontSize=if(compact) 14.sp else 16.sp),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=if(description!=null) Modifier.semantics {contentDescription=description} else Modifier)
        }
    }
}
