package tv.nakash.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import tv.nakash.R
import tv.nakash.ui.theme.NakashColors

/** A quiet inline back affordance; remote Back performs the same action. */
@Composable
fun CategoryHeading(title:String,onBack:()->Unit,subtitle:String?=null,modifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Surface(onClick=onBack,modifier=Modifier.size(36.dp),
            shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
            colors=ClickableSurfaceDefaults.colors(containerColor=Color.Transparent,focusedContainerColor=Color.White.copy(alpha=.16f)),
            scale=ClickableSurfaceDefaults.scale(focusedScale=1f)) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Icon(Icons.Default.ArrowForward,contentDescription=stringResource(R.string.back_to_browse),tint=Color.White,modifier=Modifier.size(22.dp))
            }
        }
        Text(title,style=MaterialTheme.typography.titleLarge.copy(fontSize=20.sp),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,false))
        subtitle?.let {Text(it,color=NakashColors.Muted,style=MaterialTheme.typography.labelLarge.copy(fontSize=12.sp))}
    }
}
