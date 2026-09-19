package tv.nakash.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import tv.nakash.ui.components.NetflixRow
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tv.nakash.data.local.*
import tv.nakash.ui.components.PosterCard
import tv.nakash.ui.theme.NakashColors

private data class ShelfTitle(val id: Int, val title: String, val year: Int?, val image: String?, val backdrop: String?, val plot: String?, val genres: String, val rating: Double?, val categories: String, val progress:Float?=null,val resumeLabel:String?=null)
private data class Shelf(val key: String, val title: String, val items: List<ShelfTitle>)

/** TV browsing: bounded shelves, a stable hero, and an explicit full-category view. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DiscoverScreen(nav: NavHostController, kind: String, vm: LibraryViewModel = hiltViewModel()) {
    val seriesMode = kind == "series"
    val movies by vm.movies.collectAsState()
    val series by vm.series.collectAsState()
    val saved by remember(seriesMode) {vm.user.libraryContinueWatching(seriesMode)}.collectAsState(emptyList())
    val categories by remember(kind) { vm.catalog.categories(if(seriesMode) "series" else "vod") }.collectAsState(emptyList())
    val loading by vm.loading.collectAsState()
    val status by vm.status.collectAsState()
    var selectedCategory by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    val shelfScroll=rememberLazyListState()
    var focusedShelf by rememberSaveable(kind) {mutableStateOf<String?>(null)}
    val manualShelfScroll=remember {object:BringIntoViewSpec {
        override fun calculateScrollDistance(offset:Float,size:Float,containerSize:Float)=0f
    }}
    val rowState=rememberSaveableStateHolder()
    val endFocus=remember {mutableMapOf<String,FocusRequester>()}
    var returnToShelf by remember {mutableStateOf<String?>(null)}
    fun closeCategory() {returnToShelf=selectedCategory;selectedCategory=null}
    BackHandler(selectedCategory!=null) {closeCategory()}
    var focused by remember(kind) { mutableStateOf<ShelfTitle?>(null) }
    val titles by produceState(emptyList<ShelfTitle>(), movies, series, kind) {
        value = withContext(Dispatchers.Default) {
            if(seriesMode) series.map { ShelfTitle(it.id,it.title,it.year,it.cover,it.backdrop,it.plot,it.genres,it.rating,it.categoryIds) }
            else movies.map { ShelfTitle(it.id,it.title,it.year,it.poster,it.backdrop,it.plot,it.genres,it.rating,it.categoryIds) }
        }
    }
    val continued by produceState(emptyList<ShelfTitle>(),titles,saved) {
        value=withContext(Dispatchers.Default) {
            val indexed=titles.associateBy {it.id}
            saved.distinctBy {if(seriesMode) it.seriesId?.toString() ?: it.refId else it.refId}.mapNotNull {p ->
                val id=if(seriesMode) p.seriesId else p.refId.toIntOrNull()
                indexed[id]?.let {title ->
                    val episode=if(seriesMode) vm.catalog.episodeById(p.refId) else null
                    title.copy(progress=if(p.durationMs>0) (p.positionMs.toFloat()/p.durationMs).coerceIn(0f,1f) else 0f,
                        resumeLabel=if(episode!=null) "עונה ${episode.season} · פרק ${episode.number}" else "נותרו ${(p.durationMs-p.positionMs).coerceAtLeast(0)/60000} דקות")
                }
            }
        }
    }
    val favorites by vm.favorites.collectAsState()
    val shelves by produceState(emptyList<Shelf>(), titles, categories,continued,favorites) {
        value = withContext(Dispatchers.Default) {
            buildList {
                if(continued.isNotEmpty()) add(Shelf("continue","המשך צפייה",continued.take(12)))
                val indexed=titles.associateBy {it.id}
                val mine=favorites.filter {it.kind==(if(seriesMode) "series" else "movie")}.sortedByDescending {it.addedAt}.mapNotNull {indexed[it.refId.toIntOrNull()]}
                if(mine.isNotEmpty()) add(Shelf("mylist","הרשימה שלי",mine.take(12)))
                if(titles.isNotEmpty()) add(Shelf("new",if(seriesMode) "פרקים חדשים" else "חדש בשירות",titles.take(12)))
                val rated=titles.filter { (it.rating ?: 0.0)>=7.0 }.sortedByDescending { it.rating }.take(12)
                if(rated.isNotEmpty()) add(Shelf("rated","שווה לראות",rated))
                categories.forEach { cat ->
                    val subset=titles.asSequence().filter { cat.id.toString() in it.categories.split(',') }.take(12).toList()
                    if(subset.isNotEmpty()) add(Shelf("cat${cat.id}",cat.name,subset))
                }
            }
        }
    }
    val focusedShelfIndex=shelves.indexOfFirst {it.key==focusedShelf}
    LaunchedEffect(focusedShelfIndex,selectedCategory) {
        if(selectedCategory==null && focusedShelfIndex>=0) shelfScroll.animateScrollToItem(focusedShelfIndex)
    }
    val filtered by produceState(emptyList<ShelfTitle>(), selectedCategory,titles,continued,favorites) {
        value=withContext(Dispatchers.Default) {
            when(selectedCategory) {
                "continue" -> continued
                "mylist" -> favorites.filter {it.kind==(if(seriesMode) "series" else "movie")}.sortedByDescending {it.addedAt}.mapNotNull {f->titles.firstOrNull {it.id==f.refId.toIntOrNull()}}
                "new" -> titles
                "rated" -> titles.filter {(it.rating ?: 0.0)>=7.0}.sortedByDescending {it.rating}
                else -> titles.filter {selectedCategory?.removePrefix("cat") in it.categories.split(',')}
            }
        }
    }
    val hero=focused ?: titles.firstOrNull()
    val initialFocus=remember { FocusRequester() }
    LaunchedEffect(selectedCategory, shelves.isNotEmpty(), filtered.isNotEmpty()) {
        if(if(selectedCategory==null) shelves.isNotEmpty() else filtered.isNotEmpty()) { delay(180); runCatching {(returnToShelf?.let {endFocus[it]} ?: initialFocus).requestFocus()};returnToShelf=null }
    }
    LaunchedEffect(kind) { if((if(seriesMode) vm.series.value.isEmpty() else vm.movies.value.isEmpty())) vm.refresh(seriesMode) }
    fun open(item:ShelfTitle) { nav.navigate(if(seriesMode) "seriesDetail/${item.id}" else "movie/${item.id}") }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight=maxHeight*.49f
        val shelfBottomPadding=(maxHeight-heroHeight-100.dp).coerceAtLeast(24.dp)
        if(selectedCategory!=null) {
            // Netflix-style category gallery: a full-screen poster grid under the nav bar, no hero.
            Column(Modifier.fillMaxSize().padding(top=tv.nakash.ui.nav.NavBarHeight)) {
                tv.nakash.ui.components.CategoryHeading(shelves.firstOrNull {it.key==selectedCategory}?.title ?: "כל התכנים",::closeCategory,"${filtered.size} כותרים")
                LazyVerticalGrid(GridCells.Adaptive(142.dp),Modifier.weight(1f).focusGroup(),contentPadding=PaddingValues(start=32.dp,end=32.dp,top=10.dp,bottom=32.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(filtered,key={_,it->it.id}) { i,item -> Box(if(i==0) Modifier.focusRequester(initialFocus).focusGroup() else Modifier) { PosterCard(item.title,item.year,item.image,item.progress,{focused=item},{open(item)},item.resumeLabel ?: item.title,expandable=false) } }
                }
            }
            return@BoxWithConstraints
        }
        Box(Modifier.fillMaxWidth().height(heroHeight)) {
            hero?.let { h ->
                AsyncImage(h.backdrop ?: h.image,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop,alpha=if(h.backdrop==null) .42f else .85f)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent,NakashColors.Bg.copy(.4f),NakashColors.Bg))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,NakashColors.Bg))))
                Column(Modifier.align(Alignment.BottomStart).padding(start=28.dp,end=28.dp,bottom=26.dp).fillMaxWidth(.56f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(if(seriesMode) "סדרות · בשביל הערב שלך" else "סרטים · משהו טוב לראות",color=NakashColors.Accent,style=MaterialTheme.typography.labelLarge.copy(fontSize=12.sp))
                    Text(h.title,style=MaterialTheme.typography.displayLarge.copy(fontSize=32.sp,lineHeight=36.sp),maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(listOfNotNull(h.year?.toString(),h.rating?.takeIf { it>0 }?.let { "★ %.1f".format(it) },h.genres.replace(","," · ").takeIf { it.isNotBlank() }).joinToString("  ·  "),color=NakashColors.Muted,style=MaterialTheme.typography.labelLarge.copy(fontSize=12.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                    h.plot?.let { Text(it,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyLarge.copy(fontSize=14.sp,lineHeight=19.sp)) }
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(top=heroHeight)) {
            if(titles.isEmpty()) Column(Modifier.padding(32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(loading) "מכינים את הספרייה שלך…" else status ?: "הספרייה עדיין לא נטענה",color=NakashColors.Muted)
                if(!loading) Action("טעינת הספרייה",{vm.refresh(seriesMode)})
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides manualShelfScroll) {
            LazyColumn(Modifier.weight(1f).clipToBounds().focusGroup(),state=shelfScroll,contentPadding=PaddingValues(bottom=shelfBottomPadding),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                rowItemsIndexed(shelves,key={_,sh->sh.key}) { shelfIndex,shelf ->
                    Column {
                        Text(shelf.title,Modifier.padding(start=32.dp,top=8.dp),style=MaterialTheme.typography.titleLarge.copy(fontSize=16.sp))
                        rowState.SaveableStateProvider(shelf.key) {
                            NetflixRow {
                                rowItemsIndexed(shelf.items,key={_,it->it.id}) { i,item ->
                                    Box(if(shelfIndex==0&&i==0) Modifier.focusRequester(initialFocus).focusGroup() else Modifier) {
                                    if(shelf.key=="continue") LibraryContinueCard(item,{focusedShelf=shelf.key;focused=item},{open(item)})
                                    else PosterCard(item.title,item.year,item.image,null,{focusedShelf=shelf.key;focused=item},{open(item)},backdrop=item.backdrop)
                                    }
                                }
                                item(key="all") {
                                    Surface(onClick={selectedCategory=shelf.key},modifier=Modifier.width(if(shelf.key=="continue") 200.dp else 142.dp).height(if(shelf.key=="continue") 112.dp else 213.dp).focusRequester(endFocus.getOrPut(shelf.key) {FocusRequester()}).onFocusChanged {if(it.isFocused) focusedShelf=shelf.key},
                                        shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),colors=ClickableSurfaceDefaults.colors(containerColor=NakashColors.S2,focusedContainerColor=NakashColors.S3,contentColor=Color.White,focusedContentColor=Color.White)) {
                                        Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                                            Text("←",style=MaterialTheme.typography.headlineLarge)
                                            Spacer(Modifier.height(10.dp))
                                            Text("הצג הכול",style=MaterialTheme.typography.titleMedium.copy(fontSize=15.sp))
                                            Text(shelf.title,maxLines=2,overflow=TextOverflow.Ellipsis,color=NakashColors.Muted,style=MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun LibraryContinueCard(item:ShelfTitle,focus:()->Unit,open:()->Unit) {
    Surface(onClick=open,modifier=Modifier.width(200.dp).height(112.dp).onFocusChanged {if(it.isFocused) focus()},
        shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1.04f),
        colors=ClickableSurfaceDefaults.colors(containerColor=NakashColors.S2,focusedContainerColor=NakashColors.S2),
        border=ClickableSurfaceDefaults.border(focusedBorder=androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp,Color.White),shape=RoundedCornerShape(10.dp)))) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(item.backdrop ?: item.image,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.94f)))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(item.title,color=Color.White,style=MaterialTheme.typography.titleMedium.copy(fontSize=14.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(item.resumeLabel ?: "",color=Color.White.copy(alpha=.75f),style=MaterialTheme.typography.bodySmall.copy(fontSize=11.sp),maxLines=1)
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha=.25f))) {Box(Modifier.fillMaxWidth(item.progress ?: 0f).fillMaxHeight().background(Color.White))}
                }
            }
        }
    }
}
