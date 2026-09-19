package tv.nakash.ui.vod
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
@Composable fun MovieDetailScreen(nav: NavHostController,id: Int) = tv.nakash.ui.series.SeriesDetailScreen(nav,id,series=false)
